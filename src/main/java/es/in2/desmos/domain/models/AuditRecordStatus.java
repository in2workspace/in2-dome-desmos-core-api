package es.in2.desmos.domain.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuditRecordStatus {

    RECEIVED("received"),
    CREATED("created"),
    RETRIEVED("retrieved"),
    PUBLISHED("published");

    private final String description;

}
