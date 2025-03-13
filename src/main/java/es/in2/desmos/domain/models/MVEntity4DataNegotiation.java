package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record MVEntity4DataNegotiation(
        @JsonProperty("id") @NotBlank String id,
        @JsonProperty("type") @NotBlank String type,
        @JsonProperty("version") @NotBlank String version,
        @JsonProperty("lastUpdate") @NotBlank String lastUpdate,
        @JsonProperty("lifecycleStatus") @NotBlank String lifecycleStatus,
        @JsonProperty("validFor") @NotBlank String startDateTime,
        @JsonProperty("endDateTime") @NotBlank String endDateTime,
        @JsonProperty("hash") @NotBlank String hash,
        @JsonProperty("hashlink") @NotBlank String hashlink) {

    @JsonIgnore
    public Float getFloatVersion() {
        String versionValue = version.startsWith("v") ? version.substring(1) : version;
        return Float.parseFloat(versionValue);
    }

    @JsonIgnore
    public Instant getInstantLastUpdate() {
        try {
            return Instant.parse(lastUpdate);
        } catch (Exception e) {
            System.out.println("falla la e: " + e);
            System.out.println("falla: " + lastUpdate);
            return null;
        }
    }

    @JsonIgnore
    public boolean hasVersion() {
        return version != null && !version.isBlank();
    }
}