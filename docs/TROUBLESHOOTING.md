# Desmos API - Troubleshooting Guide

## Table of Contents

1. [Introduction](#introduction)
2. [Application Startup Issues](#application-startup-issues)
3. [Connection Problems](#connection-problems)
4. [Context Broker Issues](#context-broker-issues)
5. [DLT Adapter Issues](#dlt-adapter-issues)
6. [Database Problems](#database-problems)
7. [P2P Synchronization Issues](#p2p-synchronization-issues)
8. [Authentication and Security Issues](#authentication-and-security-issues)
9. [Common Errors and Solutions](#common-errors-and-solutions)
10. [Diagnostic Tools](#diagnostic-tools)
11. [Logging and Monitoring](#logging-and-monitoring)
12. [FAQs](#faqs)

---

## Introduction

This guide provides solutions for the most common issues that may arise during the installation, configuration, and
operation of **Desmos API**. Desmos API is a Blockchain Connector that facilitates interaction between the Context
Broker (Off-Chain Storage) and the DLT Adapter (On-Chain Storage).

### Component Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Context Broker │◀───▶│   DESMOS API    │◀───▶│   DLT Adapter   │
│   (Scorpio)     │     │                 │     │   (DigitelTS)   │
└─────────────────┘     └────────┬────────┘     └─────────────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │   PostgreSQL    │
                        │   (Audit DB)    │
                        └─────────────────┘
```

---

## Application Startup Issues

### 1. Application fails to start

#### Symptoms

- Application crashes on startup
- Non-zero exit code
- Logs indicate premature termination

#### Possible causes and solutions

**A. Port already in use**

```
Error: Web server failed to start. Port 8080 was already in use.
```

**Solution:**

```bash
# On Linux/WSL, identify the process using the port
lsof -i :8080
# or
netstat -tulpn | grep 8080

# Terminate the process
kill -9 <PID>

# Or change the port in application.yml
server:
  port: 8081
```

**B. Database connection failure**

```
Error: Unable to connect to PostgreSQL
```

**Solution:**

1. Verify that PostgreSQL is running:

```bash
docker ps | grep postgres
```

2. Check the configuration in `application.yml`:

```yaml
spring:
  r2dbc:
    url: "r2dbc:postgresql://localhost:5433/it_db"
    username: "postgres"
    password: "postgres"
  flyway:
    url: "jdbc:postgresql://localhost:5433/it_db"
```

3. Verify connectivity:

```bash
psql -h localhost -p 5433 -U postgres -d it_db
```

**C. Broker or Blockchain subscription failure**

```
Error: SubscriptionCreationException: Failed to subscribe to broker
```

**Solution:**

1. Verify that the Context Broker (Scorpio) is accessible
2. Check the broker configuration:

```yaml
broker:
  provider: "scorpio"
  internalDomain: "http://scorpio:9090"
```

3. The application retries 4 times with a 2-second backoff. If it continues to fail, review the Context Broker logs.

### 2. Invalid Spring profile error

#### Symptoms

```
InvalidProfileException: Invalid Spring profile
```

#### Solution

Verify that the active profile is valid (`dev`, `test`, `prod`):

```bash
# Environment variable
export SPRING_PROFILES_ACTIVE=dev

# Or in docker-compose
environment:
  - SPRING_PROFILES_ACTIVE=dev
```

---

## Connection Problems

### 1. HTTP connection timeouts

#### Symptoms

```
RequestErrorException: Connection timeout
```

#### Solution

1. Check network connectivity between components
2. Increase timeouts if necessary
3. Verify firewalls and network policies

```bash
# Verify connectivity
curl -v http://scorpio:9090/ngsi-ld/v1/entities
curl -v http://dlt-adapter:8080/api/v1/events
```

### 2. DNS does not resolve service names

#### Symptoms

```
UnknownHostException: scorpio
```

#### Solution

1. If using Docker Compose, ensure services are on the same network
2. Verify hostnames in configuration
3. Use IPs directly for testing:

```yaml
broker:
  internalDomain: "http://192.168.1.100:9090"
```

---

## Context Broker Issues

### 1. Subscription creation error

#### Symptoms

```
SubscriptionCreationException: Unable to create subscription
UnauthorizedBrokerSubscriptionException: Unauthorized
```

#### Causes and solutions

**A. Context Broker not available**

```bash
# Check broker status
curl http://scorpio:9090/health
```

**B. Incorrect notification endpoint**

```yaml
ngsi-subscription:
  notificationEndpoint: "http://desmos:8080/api/v2/notifications/broker"
```

Verify that `desmos` is resolvable from the Context Broker.

### 2. Entity retrieval error

#### Symptoms

```
BrokerEntityRetrievalException: Failed to retrieve entity
```

#### Solution

1. Verify that the entity exists:

```bash
curl http://scorpio:9090/ngsi-ld/v1/entities/{entityId}
```

2. Check the configured paths:

```yaml
broker:
  paths:
    entities: "/ngsi-ld/v1/entities"
    entityOperations: "/ngsi-ld/v1/entityOperations"
```

### 3. Notification parsing error

#### Symptoms

```
BrokerNotificationParserException: Unable to parse notification
JsonReadingException: Error reading JSON
```

#### Solution

1. Verify the NGSI-LD notification format
2. Review logs to see the received payload
3. Ensure the Content-Type is `application/json`

---

## DLT Adapter Issues

### 1. Blockchain publication error

#### Symptoms

```
RequestErrorException: Failed to publish to blockchain
```

#### Solution

1. Verify connectivity to the DLT Adapter:

```bash
curl http://dlt-adapter:8080/api/v1/events
```

2. Check configuration:

```yaml
dlt-adapter:
  provider: "digitelts"
  internalDomain: "http://dlt-adapter:8080"
  paths:
    publication: "/api/v1/publishEvent"
```

### 2. Event subscription error

#### Symptoms

```
SubscriptionCreationException: Failed to subscribe to DLT
```

#### Solution

1. Verify that the notification endpoint is accessible from the DLT Adapter:

```yaml
tx-subscription:
  notificationEndpoint: "http://desmos:8080/api/v2/notifications/dlt"
```

### 3. DLT address deserialization error

#### Symptoms

```
DltAddressDeserializationException: Failed to deserialize DLT address
```

#### Solution

Verify that the private key is correctly configured:

```yaml
security:
  privateKey: "0xd1d346bbb4e3748b370c5985face9a4e5b402dcf41d3f715a455d08144b2327f"
```

---

## Database Problems

### 1. Flyway migration error

#### Symptoms

```
FlywayException: Migration failed
```

#### Solution

1. Verify that R2DBC and Flyway URLs are consistent:

```yaml
spring:
  r2dbc:
    url: "r2dbc:postgresql://localhost:5433/it_db"
  flyway:
    url: "jdbc:postgresql://localhost:5433/it_db"
```

2. Check the migration status:

```sql
SELECT *
FROM flyway_schema_history;
```

3. If necessary, repair migrations:

```bash
./gradlew flywayRepair
```

### 2. Audit record creation error

#### Symptoms

```
AuditRecordCreationException: Failed to create audit record
```

#### Solution

1. Verify that the audit table exists:

```sql
\dt
audit_records
```

2. Check database user permissions

### 3. R2DBC connection issues

#### Symptoms

```
R2dbcNonTransientResourceException: Connection refused
```

#### Solution

```bash
# Verify PostgreSQL is accepting connections
pg_isready -h localhost -p 5433

# Check connection limits
SHOW max_connections;
```

---

## P2P Synchronization Issues

### 1. Data discovery error

#### Symptoms

```
DiscoverySyncException: Discovery sync failed
```

#### Solution

1. Verify the trusted access nodes list:

```yaml
access-node:
  trustedAccessNodesList: "https://raw.githubusercontent.com/DOME-Marketplace/trust-framework/refs/heads/main/sbx/trusted_access_nodes_list.yaml"
```

2. Check connectivity with external nodes:

```bash
curl https://<external-node>/api/v2/sync/p2p/discovery
```

### 2. Entity synchronization error

#### Symptoms

```
EntitySyncException: Entity sync failed
```

#### Solution

1. Verify the configured genesis nodes:

```yaml
external-access-nodes:
  urls: "https://node1.dome.eu,https://node2.dome.eu"
```

2. Check JWT authentication for P2P endpoints

### 3. Integrity verification failure (Hash)

#### Symptoms

```
HashLinkException: Hash verification failed
HashCreationException: Unable to create hash
```

#### Solution

1. The received data does not match the hash registered on the blockchain
2. Verify that the entity was not modified after registration
3. Contact the source node to verify integrity

---

## Authentication and Security Issues

### 1. Invalid JWT token

#### Symptoms

```
InvalidTokenException: Token validation failed
JWTVerificationException: JWT verification error
```

#### Solution

1. Check verifier configuration:

```yaml
verifier:
  url: "https://verifier.dome-marketplace-sbx.org"
```

2. Verify that the token has not expired
3. Check the Authorization header format:

```
Authorization: Bearer <token>
```

### 2. Unauthorized DOME participant

#### Symptoms

```
UnauthorizedDomeParticipantException: Participant not authorized
```

#### Solution

1. Verify that the organizationIdentifier is registered:

```yaml
operator:
  organizationIdentifier: "VATES-S9999999E"
```

2. Verify that the participant is in the trusted nodes list

### 3. Missing JWT claims

#### Symptoms

```
JWTClaimMissingException: Required claim missing
```

#### Solution

Verify that the JWT token contains all required claims:

- `iss` (issuer)
- `sub` (subject)
- `exp` (expiration)
- DOME domain-specific claims

---

## Common Errors and Solutions

### Quick Reference Table

| Error                                      | HTTP Code | Probable Cause              | Solution                           |
|--------------------------------------------|-----------|-----------------------------|------------------------------------|
| `SubscriptionCreationException`            | 503       | Broker/DLT unavailable      | Check service connectivity         |
| `BrokerNotificationParserException`        | 400       | Malformed JSON              | Verify notification format         |
| `BrokerNotificationSelfGeneratedException` | 400       | Self-generated notification | Ignore (expected behavior)         |
| `HashCreationException`                    | 500       | Internal hash error         | Review logs, possible memory issue |
| `HashLinkException`                        | 400       | Hash mismatch               | Verify data integrity              |
| `JsonReadingException`                     | 400       | JSON parsing error          | Check input payload                |
| `AuditRecordCreationException`             | 400       | Audit DB error              | Check PostgreSQL connection        |
| `RequestErrorException`                    | 400       | HTTP request error          | Check target endpoint              |
| `BrokerEntityRetrievalException`           | 400       | Entity not found            | Verify entity exists               |
| `UnauthorizedDomeParticipantException`     | 401       | Unauthorized participant    | Check DOME registration            |
| `UnauthorizedBrokerSubscriptionException`  | 401       | No subscription permissions | Check broker credentials           |
| `InvalidProfileException`                  | 500       | Invalid Spring profile      | Use dev/test/prod                  |
| `InvalidTokenException`                    | 500       | Invalid JWT token           | Regenerate token                   |
| `EntitySyncException`                      | 500       | P2P sync failure            | Check node connectivity            |
| `DiscoverySyncException`                   | 500       | Discovery failure           | Check trusted nodes list           |
| `DltAddressDeserializationException`       | 400       | Invalid private key         | Check privateKey format            |

---

## Diagnostic Tools

### 1. Health Check

```bash
# Check application status
curl http://localhost:8080/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    },
    "r2dbc": {
      "status": "UP"
    }
  }
}
```

### 2. Prometheus Metrics

```bash
# Get metrics
curl http://localhost:8080/prometheus
```

Key metrics to monitor:

- `jvm_memory_used_bytes` - Memory usage
- `http_server_requests_seconds` - Request latency
- `logback_events_total` - Log count by level

### 3. API Documentation (Swagger)

```
http://localhost:8080/swagger-ui.html
http://localhost:8080/api-docs
```

### 4. Docker diagnostic commands

```bash
# View container logs
docker logs desmos-api -f

# Inspect the container
docker inspect desmos-api

# Execute commands inside the container
docker exec -it desmos-api sh

# Check network connectivity
docker network ls
docker network inspect <network-name>
```

### 5. Verify connectivity with external services

```bash
# Context Broker
curl -X GET "http://scorpio:9090/ngsi-ld/v1/entities" \
  -H "Accept: application/json"

# DLT Adapter
curl -X GET "http://dlt-adapter:8080/api/v1/events" \
  -H "Accept: application/json"

# PostgreSQL
psql -h localhost -p 5433 -U postgres -d it_db -c "SELECT 1"
```

---

## Logging and Monitoring

### 1. Log Configuration

Logs are configured in `src/main/resources/logback-spring.xml`.

To change the log level dynamically:

```bash
curl -X POST "http://localhost:8080/loggers/es.in2.desmos" \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

### 2. Log Levels

| Level | Usage                                        |
|-------|----------------------------------------------|
| ERROR | Critical errors requiring attention          |
| WARN  | Abnormal but recoverable situations          |
| INFO  | General operational information              |
| DEBUG | Detailed debugging information               |
| TRACE | Very detailed information (development only) |

### 3. Log Format

```
2026-03-25 10:30:45.123 INFO [desmos-blockchain-connector,traceId,spanId] --- Message
```

### 4. Common log search patterns

```bash
# Search for errors
grep -i "error\|exception" logs/application.log

# Search for connection issues
grep -i "connection\|timeout\|refused" logs/application.log

# Search for authentication issues
grep -i "unauthorized\|forbidden\|token" logs/application.log

# Search for synchronization issues
grep -i "sync\|discovery\|negotiation" logs/application.log
```

---

## FAQs

### 1. How do I manually restart data synchronization?

```bash
curl -X GET "http://localhost:8080/backoffice/v2/actions/sync"
```

### 2. Why does the application exit immediately after starting?

This is usually due to:

- Context Broker subscription failure
- DLT Adapter subscription failure
- Failure to fetch the trusted access nodes list

Review the logs to identify the specific step that failed.

### 3. How many times does the application retry failed operations?

The application uses Spring Retry with:

- **Maximum attempts**: 4
- **Delay between attempts**: 2 seconds
- **Retry condition**: `RequestErrorException`

### 4. How do I verify that queues are processing events?

Events are processed continuously through reactive streams. You can:

1. Monitor Prometheus metrics
2. Review logs with DEBUG level
3. Check audit records in PostgreSQL:

```sql
SELECT *
FROM audit_records
ORDER BY created_at DESC LIMIT 10;
```

### 5. What should I do if P2P synchronization fails constantly?

1. Check connectivity with external nodes
2. Verify that JWT tokens are valid
3. Verify that external nodes are in the trusted list
4. Review logs on both sides (local and remote)

### 6. How do I change the synchronization scheduler?

By default, synchronization runs daily at 2:00 AM. To modify it, change the cron expression in the corresponding
scheduler.

### 7. How do I build the Docker image without running tests?

```bash
docker build --build-arg SKIP_TESTS=true -t desmos-api .
```

### 8. How do I run the application in local development mode?

```bash
# With Gradle
./gradlew bootRun --args='--spring.profiles.active=dev'

# Or by setting environment variable
export SPRING_PROFILES_ACTIVE=dev
./gradlew bootRun
```