package es.in2.desmos.domain.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditRecordStatusTests {

    @Test
    void testEnumValues() {
        assertEquals("received", AuditRecordStatus.RECEIVED.getDescription());
        assertEquals("created", AuditRecordStatus.CREATED.getDescription());
        assertEquals("retrieved", AuditRecordStatus.RETRIEVED.getDescription());
        assertEquals("published", AuditRecordStatus.PUBLISHED.getDescription());
    }

    @Test
    void testEnumCount() {
        assertEquals(4, AuditRecordStatus.values().length);
    }

}