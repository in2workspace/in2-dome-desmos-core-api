package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Optional;

public record MVEntity4DataNegotiation(
        @JsonProperty("id")  String id,
        @JsonProperty("type")  String type,
        @JsonProperty("version")  String version,
        @JsonProperty("lastUpdate")  String lastUpdate,
        @JsonProperty("lifecycleStatus")  String lifecycleStatus,
        @JsonProperty("validFor")  String startDateTime,
        @JsonProperty("endDateTime")  String endDateTime,
        @JsonProperty("hash")  String hash,
        @JsonProperty("hashlink")  String hashlink) {

    @JsonIgnore
    public Float getFloatVersion() {
        String versionValue = version.startsWith("v") ? version.substring(1) : version;
        return Float.parseFloat(versionValue);
    }

    @JsonIgnore
    public Optional<Instant> getInstantLastUpdate() {
        try {
            return Optional.of(Instant.parse(lastUpdate));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @JsonIgnore
    public boolean hasVersion() {
        return version != null && !version.isBlank();
    }
}