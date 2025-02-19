CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE SCHEMA IF NOT EXISTS desmos;

CREATE TABLE IF NOT EXISTS audit_records
(
    id              uuid PRIMARY KEY UNIQUE DEFAULT uuid_generate_v4(),
    process_id      varchar(256),
    created_at      TIMESTAMP NOT NULL,
    entity_id       varchar(256),
    entity_type     varchar(256),
    entity_hash     varchar(256),
    entity_hashlink varchar(256),
    data_location   varchar(256),
    status          varchar(256),
    trader          varchar(256),
    hash            varchar(256),
    hashlink        varchar(256)
);

CREATE TABLE IF NOT EXISTS dome_participants
(
    id               uuid PRIMARY KEY UNIQUE DEFAULT uuid_generate_v4(),
    ethereum_address varchar(256)
);