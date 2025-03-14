package es.in2.desmos.domain.models;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Mono;

import java.util.List;

public record DataNegotiationEvent(
        @NotNull String processId,
        @NotNull Mono<@NotEmpty String> issuer,
        @NotNull Mono<@NotNull List<@NotNull MVEntity4DataNegotiation>> externalEntitiesInfo,
        @NotNull Mono<@NotNull List<@NotNull MVEntity4DataNegotiation>> localEntitiesInfo) {
}
