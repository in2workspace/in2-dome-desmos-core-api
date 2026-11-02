package es.in2.desmos.domain.services.blockchain.adapter.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.DltPublicationException;
import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.domain.utils.LogUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientRequest;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static es.in2.desmos.domain.utils.ApplicationConstants.HASHLINK_PREFIX;
import static es.in2.desmos.domain.utils.ApplicationUtils.getEnvironmentMetadata;
import static es.in2.desmos.domain.utils.ApplicationUtils.anyCause;
import static es.in2.desmos.domain.utils.ApplicationUtils.isRequestNotDelivered;
import static es.in2.desmos.domain.utils.ApplicationUtils.isSafeToResend;
import static es.in2.desmos.domain.utils.LogUtils.PUBLISH;
import static es.in2.desmos.domain.utils.LogUtils.truncate;
import static es.in2.desmos.domain.utils.MessageUtils.DLT_PUBLICATION_REJECTED_MESSAGE;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlockchainAdapterServiceImpl implements BlockchainAdapterService {

    // Waits 2s, 4s and 8s (with jitter) between attempts, enough to ride out an adapter restart
    private static final int PUBLICATION_MAX_RETRIES = 3;
    private static final List<String> TX_HASH_FIELDS = List.of("transactionHash", "txHash", "hash", "tx");
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");

    private final DLTAdapterProperties dltAdapterProperties;
    private final ApiConfig apiConfig;
    private final LoggingProperties loggingProperties;
    private final ObjectMapper objectMapper;

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
                // Not retried here: the startup step that looks the subscriptions up retries it as a whole
                .flatMapMany(Flux::fromArray)
                .doFirst(() -> log.debug("ProcessId: {} - Getting subscriptions...", processId));
    }

    public Mono<Void> postTxPayload(String processId, BlockchainTxPayload blockchainTxPayload) {
        String entityType = blockchainTxPayload.eventType();
        String entityId = entityIdFromDataLocation(blockchainTxPayload.dataLocation());
        int maxBodyChars = loggingProperties.payloadMaxChars();
        AtomicInteger attempts = new AtomicInteger();
        return Mono.defer(() -> {
                    // Timed per attempt, so a retry does not add the failed attempts to the logged duration
                    int attempt = attempts.incrementAndGet();
                    long startNanos = System.nanoTime();
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
                                                log.debug(DLT_PUBLICATION_REJECTED_MESSAGE, processId,
                                                        response.statusCode(), blockchainTxPayload.dataLocation(), errorBody);
                                                return Mono.<Void>error(new DltPublicationRejectedException(response.statusCode().value(),
                                                        "HTTP " + response.statusCode().value() + ": " + truncate(errorBody, maxBodyChars)));
                                            });
                                }
                                // The body is read (not released) to log the transaction hash when the adapter returns it
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .doOnNext(body -> log.info("{} ProcessID: {} - {} {} -> tx={} ({}{})", PUBLISH, processId,
                                                entityType, entityId, extractTxHash(body).orElse("n/a"),
                                                LogUtils.formatDurationSince(startNanos),
                                                attempt > 1 ? ", attempts=" + attempt : ""))
                                        .then();
                            });
                })
                .retryWhen(Retry.backoff(PUBLICATION_MAX_RETRIES, publicationRetryBackoff)
                        .filter(BlockchainAdapterServiceImpl::isSafeToRetryPublication)
                        .doBeforeRetry(retrySignal -> log.debug("{} ProcessID: {} - {} {} -> attempt {} failed, retrying: {}",
                                PUBLISH, processId, entityType, entityId, retrySignal.totalRetries() + 1,
                                retrySignal.failure().getMessage()))
                        .onRetryExhaustedThrow((retrySpec, retrySignal) -> retrySignal.failure()))
                .onErrorResume(e -> failPublication(e, processId, blockchainTxPayload, attempts.get()));
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

        DltPublicationRejectedException(int status, String message) {
            super(message, status, "", new HttpHeaders(), null, null);
        }
    }

    // dataLocation is "<domain>/.../entities/<entityId>?hl=<hashlink>"; the payload's entityId is a hash.
    private static String entityIdFromDataLocation(String dataLocation) {
        if (dataLocation == null) {
            return null;
        }
        int hashLinkIndex = dataLocation.indexOf(HASHLINK_PREFIX);
        String withoutHashLink = hashLinkIndex >= 0 ? dataLocation.substring(0, hashLinkIndex) : dataLocation;
        return withoutHashLink.substring(withoutHashLink.lastIndexOf('/') + 1);
    }

    private Optional<String> extractTxHash(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        String trimmed = body.trim();
        if (TX_HASH_PATTERN.matcher(trimmed).matches()) {
            return Optional.of(trimmed);
        }
        try {
            JsonNode json = objectMapper.readTree(trimmed);
            for (String field : TX_HASH_FIELDS) {
                JsonNode value = json.get(field);
                if (value != null && value.isTextual()) {
                    return Optional.of(value.asText());
                }
            }
        } catch (JsonProcessingException e) {
            log.debug("DLT Adapter publication response is not JSON: {}", e.getMessage());
        }
        return Optional.empty();
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
    private Mono<Void> failPublication(Throwable error, String processId, BlockchainTxPayload blockchainTxPayload,
                                       int attempts) {
        String entityId = entityIdFromDataLocation(blockchainTxPayload.dataLocation());
        if (isPublicationOutcomeUnknown(error)) {
            log.error("{} ProcessID: {} - {} {} -> outcome unknown after {} attempt(s), the adapter received it but did not "
                            + "answer: {}; the transaction may still reach the blockchain, check it before publishing it again",
                    PUBLISH, processId, blockchainTxPayload.eventType(), entityId, attempts, error.getMessage());
        } else {
            log.error("{} ProcessID: {} - {} {} -> failed after {} attempt(s): {}", PUBLISH, processId,
                    blockchainTxPayload.eventType(), entityId, attempts, error.getMessage());
        }
        return Mono.error(new DltPublicationException(error.getMessage(), error));
    }

}
