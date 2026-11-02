package es.in2.desmos.domain.models;

/**
 * What writing an entity to the local broker did: it did not exist and was created, or it existed and was updated.
 */
public enum BrokerWriteAction {
    CREATED,
    UPDATED
}
