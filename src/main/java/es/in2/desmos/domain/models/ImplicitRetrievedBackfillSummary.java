package es.in2.desmos.domain.models;

/**
 * Counters of one run of the implicit RETRIEVED backfill.
 *
 * @param roots                entities with a PUBLISHED/CONSUMER record, whose subentities are read
 * @param subentities          entities read from the broker that are not one of the roots
 * @param skippedByType        subentities of a type outside the root types: the broker never notifies them
 * @param skippedByUnknownHash subentities whose current content has no audit record: it may be a local edit
 * @param upToDate             subentities whose last RETRIEVED or DELETED record already has their current hash
 * @param inserted             implicit RETRIEVED records written
 * @param failed               subentities that could not be checked or recorded
 */
public record ImplicitRetrievedBackfillSummary(long roots, long subentities, long skippedByType,
                                               long skippedByUnknownHash, long upToDate, long inserted,
                                               long failed) {
}
