package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public final class BrokerEntityWithIdTypeLastUpdateAndVersion extends BrokerEntityWithIdAndType {
    @JsonProperty("version")
    private final Version version;

    @JsonProperty("lastUpdate")
    private final @NotBlank String lastUpdate;

    @JsonProperty("lifecycleStatus")
    private final @NotBlank String lifecycleStatus;

    @JsonProperty("validFor")
    private final @NotBlank BrokerEntityValidFor validFor;

    public BrokerEntityWithIdTypeLastUpdateAndVersion(String id, String type, Version version, String lastUpdate, String lifecycleStatus, BrokerEntityValidFor validFor) {
        super(id, type);
        this.version = version;
        this.lastUpdate = lastUpdate;
        this.lifecycleStatus = lifecycleStatus;
        this.validFor = validFor;
    }
}
