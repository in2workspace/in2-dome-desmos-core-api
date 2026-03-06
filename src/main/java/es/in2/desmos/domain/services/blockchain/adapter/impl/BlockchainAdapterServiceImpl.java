package es.in2.desmos.domain.services.blockchain.adapter.impl;

import es.in2.desmos.domain.models.BlockchainSubscription;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.domain.services.blockchain.adapter.BlockchainAdapterService;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.properties.DLTAdapterProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static es.in2.desmos.domain.utils.ApplicationUtils.getEnvironmentMetadata;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableRetry
public class BlockchainAdapterServiceImpl implements BlockchainAdapterService {

    private final DLTAdapterProperties dltAdapterProperties;
    private final ApiConfig apiConfig;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(dltAdapterProperties.externalDomain())
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
                .retrieve()
                .onStatus(status -> status != null && status.is2xxSuccessful(),
                        response -> Mono.empty())
                .bodyToMono(Void.class)
                .retry(3)
                .onErrorResume(e -> recover(processId, blockchainTxPayload));
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

    @Recover
    public Mono<Void> recover(String processId, BlockchainTxPayload blockchainTxPayload) {
        log.debug("ProcessID: {}, Recovering after 3 retries. Data: {}", processId, blockchainTxPayload.toString());
        return Mono.empty();
    }

}
