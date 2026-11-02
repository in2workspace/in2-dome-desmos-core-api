package es.in2.desmos.domain.services.blockchain.adapter.impl;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientRequest;
import reactor.util.retry.Retry;

import java.time.Duration;

import static es.in2.desmos.domain.utils.ApplicationUtils.getEnvironmentMetadata;
import static es.in2.desmos.domain.utils.ApplicationUtils.anyCause;
import static es.in2.desmos.domain.utils.ApplicationUtils.isRequestNotDelivered;
import static es.in2.desmos.domain.utils.ApplicationUtils.isSafeToResend;
import static es.in2.desmos.domain.utils.MessageUtils.DLT_PUBLICATION_OUTCOME_UNKNOWN_MESSAGE;
import static es.in2.desmos.domain.utils.MessageUtils.DLT_PUBLICATION_REJECTED_MESSAGE;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableRetry
public class BlockchainAdapterServiceImpl implements BlockchainAdapterService {

    // Waits 2s, 4s and 8s (with jitter) between attempts, enough to ride out an adapter restart
    private static final int PUBLICATION_MAX_RETRIES = 3;

    private final DLTAdapterProperties dltAdapterProperties;
    private final ApiConfig apiConfig;

    private WebClient webClient;
    private Duration publicationRetryBackoff = Duration.ofSeconds(2);

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(dltAdapterProperties.externalDomain())
                .clientConnector(apiConfig.clientHttpConnector(dltAdapterProperties.responseTimeout()))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize((int) apiConfig.getMaxInMemorySize().toBytes()))
                .build();
    }

    @Override
    public Mono<Void> createSubscription(String processId, BlockchainSubscription blockchainSubscription) {
        log.debug("ProcessId: {} - Creating subscription...", processId);
        return webClient.post()
                .uri(dltAdapterProperties.paths()
                        .subscription())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(blockchainSubscription)
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Override
    public Flux<BlockchainSubscription> getSubscriptions(String processId) {
        return webClient.get()
                .uri(dltAdapterProperties.paths()
                        .subscription())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(BlockchainSubscription[].class)
                .retry(3)
                .flatMapMany(Flux::fromArray)
                .doFirst(() -> log.debug("ProcessId: {} - Getting subscriptions...", processId));
    }

    public Mono<Void> postTxPayload(String processId, BlockchainTxPayload blockchainTxPayload) {
        return webClient.post()
                .uri(dltAdapterProperties.paths().publication())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(blockchainTxPayload)
                // The adapter answers once the transaction is confirmed, which can take longer than other requests
                .httpRequest(httpRequest -> httpRequest.<HttpClientRequest>getNativeRequest()
                        .responseTimeout(dltAdapterProperties.publicationTimeout()))
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(errorBody -> {
                                    log.error(DLT_PUBLICATION_REJECTED_MESSAGE, processId,
                                            response.statusCode(), blockchainTxPayload.dataLocation(), errorBody);
                                    return Mono.<Void>error(new DltPublicationRejectedException(response.statusCode().value(), errorBody));
                                });
                    }
                    log.debug("ProcessId: {} - Send to DLT with status code: {} for entityId={}",
                            processId, response.statusCode(), blockchainTxPayload.dataLocation());
                    return response.releaseBody();
                })
                .retryWhen(Retry.backoff(PUBLICATION_MAX_RETRIES, publicationRetryBackoff)
                        .filter(BlockchainAdapterServiceImpl::isSafeToRetryPublication)
                        .onRetryExhaustedThrow((retrySpec, retrySignal) -> retrySignal.failure()))
                .doOnError(BlockchainAdapterServiceImpl::isPublicationOutcomeUnknown, error -> log.error(
                        DLT_PUBLICATION_OUTCOME_UNKNOWN_MESSAGE, processId, blockchainTxPayload.dataLocation(), error.getMessage()))
                .onErrorResume(e -> recover(e, processId, blockchainTxPayload));
    }

    /**
     * The request was sent (the connection was established) but no answer arrived: a response timeout or a
     * connection closed by the adapter. It may have been processed, so it is neither a success nor a rejection.
     */
    static boolean isPublicationOutcomeUnknown(Throwable error) {
        return anyCause(error, WebClientRequestException.class::isInstance) && !isRequestNotDelivered(error);
    }

    /**
     * Publishing is not idempotent: sending the same payload again after the adapter received it can create a
     * second transaction, so it is only retried when ApplicationUtils.isSafeToResend allows it.
     */
    static boolean isSafeToRetryPublication(Throwable error) {
        return isSafeToResend(error);
    }

    // A WebClientResponseException, so that isSafeToResend reads its HTTP status like any other response error
    private static final class DltPublicationRejectedException extends WebClientResponseException {

        DltPublicationRejectedException(int status, String errorBody) {
            super(errorBody, status, "", new HttpHeaders(), null, null);
        }
    }

    @Override
    public Flux<String> getEventsFromRangeOfTime(String processId, long startDate, long endDate) {
        return webClient.get()
                .uri(dltAdapterProperties.paths().events()
                        + "?startDate=" + startDate
                        + "&endDate=" + endDate
                        + "&envM=" + getEnvironmentMetadata(apiConfig.getCurrentEnvironment()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(String.class);
    }

    // The error is propagated, not swallowed: the caller must not record the entity as PUBLISHED, otherwise the
    // audit trail claims a publication that never happened and the next version of the entity is chained to a
    // hashlink that is not on the blockchain.
    @Recover
    public Mono<Void> recover(Throwable error, String processId, BlockchainTxPayload blockchainTxPayload) {
        log.error("ProcessID: {}, Publication failed after retries: {}. Data: {}", processId, error.getMessage(),
                blockchainTxPayload.toString());
        return Mono.error(error);
    }

}
