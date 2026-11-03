package es.in2.desmos.domain.models;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of one entity type during a P2P synchronization, only used for logging. The P2P sync job puts
 * an instance in the Reactor Context of each type's negotiation and DataTransferJobImpl fills it in.
 */
public final class P2PSyncTypeMetrics {

    public static final Class<P2PSyncTypeMetrics> CONTEXT_KEY = P2PSyncTypeMetrics.class;

    private final LongAdder fetched = new LongAdder();
    private final LongAdder subentities = new LongAdder();
    private final LongAdder invalidIntegrity = new LongAdder();
    private final LongAdder missing = new LongAdder();

    public void addFetched(long count) {
        fetched.add(count);
    }

    public void addSubentities(long count) {
        subentities.add(count);
    }

    public void addInvalidIntegrity(long count) {
        invalidIntegrity.add(count);
    }

    public void addMissing(long count) {
        missing.add(count);
    }

    public long fetched() {
        return fetched.sum();
    }

    public long subentities() {
        return subentities.sum();
    }

    public long invalidIntegrity() {
        return invalidIntegrity.sum();
    }

    public long missing() {
        return missing.sum();
    }
}
