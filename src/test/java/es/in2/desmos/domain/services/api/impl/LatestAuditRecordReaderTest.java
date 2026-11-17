package es.in2.desmos.domain.services.api.impl;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LatestAuditRecordReaderTest {

    private final AuditRecordRepository repository = mock(AuditRecordRepository.class);
    private final LatestAuditRecordReader reader = new LatestAuditRecordReader(repository);

    @Test
    void itShouldReturnTheMostRecentAuditRecord() {
        AuditRecord last = AuditRecord.builder().hashLink("hl").build();
        when(repository.findMostRecentAuditRecord()).thenReturn(Mono.just(last));

        StepVerifier.create(reader.fetch()).expectNext(last).verifyComplete();
        verify(repository, never()).count();
    }

    @Test
    void itShouldReturnAnEmptyAuditRecordWhenTheTableIsEmpty() {
        when(repository.findMostRecentAuditRecord()).thenReturn(Mono.empty());
        when(repository.count()).thenReturn(Mono.just(0L));

        StepVerifier.create(reader.fetch())
                .assertNext(auditRecord -> {
                    assertThat(auditRecord.getCreatedAt()).isNull();
                    assertThat(auditRecord.getHashLink()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void itShouldFailWhenNoneIsFoundAlthoughTheTableIsNotEmpty() {
        when(repository.findMostRecentAuditRecord()).thenReturn(Mono.empty());
        when(repository.count()).thenReturn(Mono.just(3L));

        StepVerifier.create(reader.fetch()).expectError(NoSuchElementException.class).verify();
    }
}
