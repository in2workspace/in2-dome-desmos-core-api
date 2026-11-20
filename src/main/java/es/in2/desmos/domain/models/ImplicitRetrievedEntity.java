package es.in2.desmos.domain.models;

/**
 * An entity this node writes to its broker as part of another entity (a subentity of a blockchain event or of a P2P
 * synchronization), with the exact body it writes.
 */
public record ImplicitRetrievedEntity(String id, String type, String body) {
}
