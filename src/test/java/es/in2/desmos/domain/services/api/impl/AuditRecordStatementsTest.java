package es.in2.desmos.domain.services.api.impl;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecordStatementsTest {

    @Test
    void aFullChunkIsOneStatement() {
        assertThat(AuditRecordStatements.statementSizes(500, 500)).containsExactly(500);
    }

    @Test
    void aRemainderIsSplitInStatementsOf100Then10Then1() {
        List<Integer> sizes = AuditRecordStatements.statementSizes(499, 500);

        assertThat(sizes).containsExactlyElementsOf(concat(
                Collections.nCopies(4, 100), Collections.nCopies(9, 10), Collections.nCopies(9, 1)));
    }

    @Test
    void anyChunkUsesAtMostFourStatementSizes() {
        Set<Integer> used = new HashSet<>();
        IntStream.rangeClosed(1, 500).forEach(rows -> {
            List<Integer> sizes = AuditRecordStatements.statementSizes(rows, 500);
            assertThat(sizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(rows);
            used.addAll(sizes);
        });

        assertThat(used).containsExactlyInAnyOrder(500, 100, 10, 1);
    }

    @Test
    void sizesLargerThanTheBatchSizeAreNotUsed() {
        Set<Integer> used = new HashSet<>();
        IntStream.rangeClosed(1, 50).forEach(rows -> used.addAll(AuditRecordStatements.statementSizes(rows, 50)));

        assertThat(used).containsExactlyInAnyOrder(50, 10, 1);
    }

    @Test
    void theInsertHasTwelveParametersPerRow() {
        String sql = AuditRecordStatements.insertSql(2);

        assertThat(sql).isEqualTo("INSERT INTO desmos.audit_records (id, process_id, created_at, entity_id, "
                + "entity_type, entity_hash, entity_hashlink, data_location, status, trader, hash, hashlink) VALUES "
                + "($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12), "
                + "($13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24)");
    }

    private static List<Integer> concat(List<Integer> a, List<Integer> b, List<Integer> c) {
        return IntStream.range(0, 3).boxed()
                .flatMap(i -> (i == 0 ? a : i == 1 ? b : c).stream())
                .toList();
    }
}
