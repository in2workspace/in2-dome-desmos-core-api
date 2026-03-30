package es.in2.desmos.domain.models;

import jakarta.annotation.Nonnull;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerEntityWithIdTypeLastUpdateAndVersionTests {

    @Test
    void itShouldReturnToString() {
        BrokerEntityWithIdAndType brokerEntityWithIdAndType =
                new BrokerEntityWithIdTypeLastUpdateAndVersion("id", "type", null, "lastUpdate", "lifecycleStatus", new BrokerEntityValidFor("validFor", "validFor"));

        String expectedResult = "BrokerEntityWithIdTypeLastUpdateAndVersion(super=BrokerEntityWithIdAndType(id=id, type=type), version=null, lastUpdate=lastUpdate, lifecycleStatus=lifecycleStatus, validFor=BrokerEntityValidFor[startDateTime=validFor, endDateTime=validFor])";

        String result = brokerEntityWithIdAndType.toString();

        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    void equals(){
        EqualsVerifier.forClass(BrokerEntityWithIdTypeLastUpdateAndVersion.class)
                .withRedefinedSuperclass()
                .withIgnoredAnnotations(Nonnull.class)
                .verify();

        EqualsVerifier.forClass(BrokerEntityWithIdAndType.class)
                .withRedefinedSubclass(BrokerEntityWithIdTypeLastUpdateAndVersion.class)
                .withIgnoredAnnotations(Nonnull.class)
                .verify();
    }


}