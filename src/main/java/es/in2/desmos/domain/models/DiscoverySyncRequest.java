package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public record DiscoverySyncRequest(
        @JsonProperty("issuer") @NotBlank @URL Mono<String> issuer,
        @JsonProperty("external_minimum_viable_entities_for_data_negotiation_list") @NotNull Flux<@NotNull MVEntity4DataNegotiation> externalMVEntities4DataNegotiation) {
}