# Desmos API - Application Flow Documentation

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Application Layers](#application-layers)
4. [Application Startup Flow](#application-startup-flow)
5. [Main Workflows](#main-workflows)
6. [API Endpoints](#api-endpoints)
7. [Scheduled Tasks](#scheduled-tasks)
8. [Data Models](#data-models)
9. [Configuration](#configuration)
10. [Security](#security)

---

## Overview

**Desmos API** is a Blockchain Connector solution developed by IN2 as part of the DOME Marketplace project. It acts as a
crucial component within the Access Node architecture, facilitating interaction between:

- **Off-Chain Storage**: Context Broker (NGSI-LD compliant, e.g., Scorpio)
- **On-Chain Storage**: Blockchain/DLT Adapter (e.g., DigitelTS)

The application is built using **Spring Boot WebFlux** (reactive programming model) and implements a *
*Publisher-Subscriber (Pub-Sub)** pattern for synchronizing data between distributed nodes in the DOME ecosystem.

### Key Features

- Reactive/non-blocking architecture using Project Reactor
- Peer-to-peer (P2P) data synchronization between Access Nodes
- Data integrity verification using SHA-256 hashing
- Audit trail for all data operations
- JWT-based authentication and authorization

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DESMOS API                                        │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                        Infrastructure Layer                              │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   │ │
│  │  │ Controllers  │  │   Security   │  │    Config    │                   │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘                   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                         Application Layer                                │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   │ │
│  │  │   Runners    │  │  Schedulers  │  │  Workflows   │                   │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘                   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                           Domain Layer                                   │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │ │
│  │  │ Services │ │  Models  │ │  Events  │ │  Repos   │ │  Utils   │       │ │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘       │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
          ┌─────────────────────────┼─────────────────────────┐
          │                         │                         │
          ▼                         ▼                         ▼
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│  Context Broker │      │   DLT Adapter   │      │    PostgreSQL   │
│   (Scorpio)     │      │   (DigitelTS)   │      │    (Audit DB)   │
└─────────────────┘      └─────────────────┘      └─────────────────┘
```

---

## Application Layers

### 1. Infrastructure Layer (`es.in2.desmos.infrastructure`)

#### Controllers

- **NotificationController**: Handles incoming notifications from both the Broker and Blockchain
- **DataSyncController**: Provides P2P synchronization endpoints for data discovery and entity synchronization
- **BackofficeController**: Administrative endpoint for manual data synchronization triggers

#### Security

- JWT-based authentication using `JwtTokenProvider`
- Machine-to-machine (M2M) access token provider
- Security filters for protected endpoints
- CORS configuration

#### Configuration

- `ApiConfig`: API-level configuration (domain, version, organization)
- `BrokerConfig`: Context Broker connection settings
- `BlockchainConfig`: DLT Adapter connection settings
- `TrustFrameworkConfig`: Trusted access nodes list management
- `ExternalAccessNodesConfig`: External genesis nodes configuration

### 2. Application Layer (`es.in2.desmos.application`)

#### Runners

- **ApplicationRunner**: Initializes the application on startup
    - Sets up Broker subscriptions
    - Sets up Blockchain subscriptions
    - Loads trusted access nodes
    - Initiates initial data synchronization
    - Starts Pub-Sub queue processing

#### Schedulers

- **AccessNodeScheduler**: Refreshes trusted access nodes list every 5 minutes
- **DataSyncScheduler**: Runs daily data synchronization at 2:00 AM

#### Workflows

- **PublishWorkflow**: Handles publishing local data changes to the blockchain
- **SubscribeWorkflow**: Handles receiving and processing blockchain notifications
- **DataSyncWorkflow**: Orchestrates P2P data synchronization with external nodes

### 3. Domain Layer (`es.in2.desmos.domain`)

#### Services

Organized by domain:

- **api/**: Core API services (AuditRecordService, QueueService)
- **blockchain/**: Blockchain interaction (BlockchainListenerService, BlockchainPublisherService)
- **broker/**: Context Broker interaction (BrokerListenerService, BrokerPublisherService)
- **sync/**: Data synchronization services (DataSyncService, DiscoverySyncWebClient, EntitySyncWebClient)
- **policies/**: Replication policies evaluation

#### Models

Key data structures:

- `BrokerNotification`: Notifications from the Context Broker
- `BlockchainNotification`: Notifications from the Blockchain
- `AuditRecord`: Immutable audit log entries
- `BlockchainTxPayload`: Data payload for blockchain transactions
- `MVEntity4DataNegotiation`: Minimal entity view for data negotiation

#### Events

- `DataNegotiationEventPublisher`: Spring event publisher for asynchronous data negotiation

---

## Application Startup Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION READY EVENT                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. SET BROKER SUBSCRIPTION                                                   │
│    - Build entity type list (product-offering, catalog, etc.)               │
│    - Create subscription with notification endpoint                          │
│    - Register with Context Broker                                            │
│    - Retry up to 4 times on failure with 2s backoff                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. SET BLOCKCHAIN SUBSCRIPTION                                               │
│    - Configure event types (root objects)                                    │
│    - Set notification endpoint                                               │
│    - Register with DLT Adapter                                               │
│    - Retry up to 4 times on failure with 2s backoff                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. GET TRUSTED ACCESS NODES LIST                                             │
│    - Fetch from Trust Framework repository                                   │
│    - Load into memory for P2P communication                                  │
│    - Retry up to 4 times on failure with 2s backoff                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. INITIALIZE DATA SYNC                                                      │
│    - Start P2P data synchronization workflow                                 │
│    - Exchange entity metadata with genesis nodes                             │
│    - Negotiate and transfer missing data                                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. AUTHORIZE QUEUES                                                          │
│    - Enable Publish Queue processing (Broker → Blockchain)                   │
│    - Enable Subscribe Queue processing (Blockchain → Broker)                 │
│    - Start continuous event stream processing                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Main Workflows

### 1. Publish Workflow (Local Data → Blockchain)

Triggered when the Context Broker notifies about local entity changes.

```
┌───────────────────┐     ┌───────────────────┐     ┌───────────────────┐
│   Context Broker  │────▶│  Notification     │────▶│  PublishWorkflow  │
│   (Entity Change) │     │  Controller       │     │                   │
└───────────────────┘     └───────────────────┘     └───────────────────┘
                                                            │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 1. Enqueue Event  │                            │ 2. Get from Queue │
│ (BrokerNotification)                           │                   │
└───────────────────┘                            └───────────────────┘
                                                            │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 3. Calculate Hash │                            │ 4. Build TX       │
│    & HashLink     │                            │    Payload        │
└───────────────────┘                            └───────────────────┘
                                                            │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 5. Save Audit     │                            │ 6. Publish to     │
│    (CREATED)      │                            │    Blockchain     │
└───────────────────┘                            └───────────────────┘
                                                            │
                                                            ▼
                                                 ┌───────────────────┐
                                                 │ 7. Save Audit     │
                                                 │    (PUBLISHED)    │
                                                 └───────────────────┘
```

**Steps:**

1. **Event Reception**: Controller receives `BrokerNotification` from Context Broker
2. **Queue Enqueue**: Event is added to the publish events queue
3. **Hash Calculation**: Calculate SHA-256 hash and hashlink (chain linking)
4. **Payload Building**: Create `BlockchainTxPayload` with entity metadata
5. **Audit Record (CREATED)**: Save audit record with status CREATED
6. **Blockchain Publication**: Publish to DLT Adapter
7. **Audit Record (PUBLISHED)**: Update audit record with status PUBLISHED

---

### 2. Subscribe Workflow (Blockchain → Local Data)

Triggered when the Blockchain notifies about external entity changes.

```
┌───────────────────┐     ┌───────────────────┐     ┌───────────────────┐
│   DLT Adapter     │────▶│  Notification     │────▶│ SubscribeWorkflow │
│   (Blockchain     │     │  Controller       │     │                   │
│    Event)         │     └───────────────────┘     └───────────────────┘
└───────────────────┘                                       │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 1. Enqueue Event  │                            │ 2. Get from Queue │
│ (BlockchainNotif) │                            │                   │
└───────────────────┘                            └───────────────────┘
                                                            │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 3. Retrieve Entity│                            │ 4. Verify Data    │
│    from External  │                            │    Integrity      │
│    Source         │                            │    (Hash Check)   │
└───────────────────┘                            └───────────────────┘
                                                            │
        ┌───────────────────────────────────────────────────┤
        │                                                   │
        ▼                                                   ▼
┌───────────────────┐                            ┌───────────────────┐
│ 5. Save Audit     │                            │ 6. Publish to     │
│    (RETRIEVED)    │                            │    Local Broker   │
└───────────────────┘                            └───────────────────┘
                                                            │
                                                            ▼
                                                 ┌───────────────────┐
                                                 │ 7. Save Audit     │
                                                 │    (PUBLISHED)    │
                                                 └───────────────────┘
```

**Steps:**

1. **Event Reception**: Controller receives `BlockchainNotification` from DLT Adapter
2. **Queue Enqueue**: Event is added to the subscribe events queue
3. **Entity Retrieval**: Fetch full entity from source broker using `dataLocation` URL
4. **Data Verification**: Verify hash integrity against blockchain record
5. **Audit Record (RETRIEVED)**: Save audit record with status RETRIEVED
6. **Broker Publication**: Publish/upsert entity to local Context Broker
7. **Audit Record (PUBLISHED)**: Update audit record with status PUBLISHED

---

### 3. P2P Data Synchronization Workflow

Peer-to-peer synchronization with external Access Nodes (Genesis Nodes).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        P2P DATA SYNC WORKFLOW                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ FOR EACH ROOT OBJECT TYPE (product-offering, catalog, etc.)                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. CREATE LOCAL MV ENTITIES                                                  │
│    - Query local broker for entities of type                                 │
│    - Extract minimal view (id, type, version, lastUpdate, hash, hashlink)   │
│    - Join with audit records for hash information                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. FILTER REPLICABLE ENTITIES                                                │
│    - Apply replication policies                                              │
│    - Check lifecycle status, validFor dates                                  │
│    - Exclude entities that shouldn't be replicated                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. EXCHANGE MV ENTITIES WITH GENESIS NODES                                   │
│    - For each external access node:                                          │
│      - Send local MV entities via POST /api/v2/sync/p2p/discovery           │
│      - Receive external MV entities in response                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. DATA NEGOTIATION                                                          │
│    - Compare local vs external entities                                      │
│    - Identify entities to ADD (new, or newer version)                       │
│    - Identify entities to UPDATE (same version, newer lastUpdate)           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. DATA TRANSFER                                                             │
│    - Request full entities from external node                                │
│      via POST /api/v2/sync/p2p/entities                                     │
│    - Receive entities with all subentities (tree structure)                 │
│    - Store to local broker                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Data Negotiation Logic:**

| Condition                                             | Action            |
|-------------------------------------------------------|-------------------|
| Entity exists externally but not locally              | **ADD**           |
| External version > Local version                      | **ADD** (replace) |
| Same version, external lastUpdate > local lastUpdate  | **UPDATE**        |
| Same version, local lastUpdate >= external lastUpdate | **SKIP**          |
| Local version > External version                      | **SKIP**          |

---

## API Endpoints

### Notification Endpoints (Public - No Auth Required)

| Method | Endpoint                       | Description                                   |
|--------|--------------------------------|-----------------------------------------------|
| POST   | `/api/v2/notifications/broker` | Receive broker notifications (entity changes) |
| POST   | `/api/v2/notifications/dlt`    | Receive blockchain/DLT notifications          |

### P2P Synchronization Endpoints (Authenticated)

| Method | Endpoint                     | Description                                        |
|--------|------------------------------|----------------------------------------------------|
| POST   | `/api/v2/sync/p2p/discovery` | Exchange MV entities for data negotiation (NDJSON) |
| POST   | `/api/v2/sync/p2p/entities`  | Request full entities by ID                        |
| GET    | `/api/v2/entities/{id}`      | Get entity by ID with subentities                  |

### Backoffice Endpoints (Authenticated)

| Method | Endpoint                      | Description                           |
|--------|-------------------------------|---------------------------------------|
| GET    | `/backoffice/v2/actions/sync` | Manually trigger data synchronization |

### Health & Metrics (Public)

| Method | Endpoint      | Description               |
|--------|---------------|---------------------------|
| GET    | `/health`     | Application health status |
| GET    | `/prometheus` | Prometheus metrics        |

---

## Scheduled Tasks

| Scheduler           | Cron Expression | Description                                        |
|---------------------|-----------------|----------------------------------------------------|
| AccessNodeScheduler | `0 */5 * * * *` | Refresh trusted access nodes list every 5 minutes  |
| DataSyncScheduler   | `0 0 2 * * *`   | Run full P2P data synchronization daily at 2:00 AM |

---

## Data Models

### Root Object Types (Entities synchronized via blockchain)

```
ROOT_OBJECTS_LIST = [
    "individual",
    "organization", 
    "catalog",
    "product-offering",
    "product-offering-price",
    "product-specification",
    "service-specification",
    "resource-specification",
    "category",
    "product-order",
    "product",
    "usage",
    "usageSpecification",
    "applied-customer-bill-rate",
    "customer-bill"
]
```

### Audit Record Status Flow

```
┌──────────┐     ┌───────────┐     ┌───────────┐
│ CREATED  │────▶│ PUBLISHED │────▶│ (stored)  │
└──────────┘     └───────────┘     └───────────┘
     │
     │ (Subscribe flow)
     ▼
┌───────────┐    ┌───────────┐     ┌───────────┐
│ RETRIEVED │───▶│ PUBLISHED │────▶│ (stored)  │
└───────────┘    └───────────┘     └───────────┘
```

### MVEntity4DataNegotiation (Minimal View for Negotiation)

```
record MVEntity4DataNegotiation(
    String id,           // Entity unique identifier
    String type,         // Entity type (e.g., "product-offering")
    String version,      // Version number
    String lastUpdate,   // Last modification timestamp
    String lifecycleStatus,  // Lifecycle state
    String startDateTime,    // ValidFor start
    String endDateTime,      // ValidFor end
    String hash,         // SHA-256 hash of entity
    String hashLink      // Chain link to previous version
)
```

---

## Configuration

### Key Configuration Properties

| Property                             | Description               | Default                   |
|--------------------------------------|---------------------------|---------------------------|
| `api.externalDomain`                 | Public URL of this node   | `http://localhost:8080`   |
| `api.version`                        | API version prefix        | `v2`                      |
| `broker.provider`                    | Context Broker provider   | `scorpio`                 |
| `broker.internalDomain`              | Context Broker URL        | `http://scorpio:9090`     |
| `dlt-adapter.provider`               | DLT Adapter provider      | `digitelts`               |
| `dlt-adapter.internalDomain`         | DLT Adapter URL           | `http://dlt-adapter:8080` |
| `operator.organizationIdentifier`    | Organization ID (VATES)   | -                         |
| `access-node.trustedAccessNodesList` | URL to trusted nodes YAML | Trust Framework URL       |
| `external-access-nodes.urls`         | Genesis nodes for sync    | -                         |

### Environment Profiles

- **dev**: Development configuration
- **test**: Testing configuration
- **prod**: Production configuration

---

## Security

### Authentication Flow

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   HTTP Request  │────▶│  Bearer Token   │────▶│  JWT Verifier   │
│   with JWT      │     │  Filter         │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                                                        │
                                                        ▼
                                               ┌─────────────────┐
                                               │  External       │
                                               │  Verifier       │
                                               │  Service        │
                                               └─────────────────┘
```

### Endpoint Security Matrix

| Endpoint Pattern              | Authentication     |
|-------------------------------|--------------------|
| `/health`, `/prometheus`      | Public             |
| `/api/v2/notifications/*`     | Public (callbacks) |
| `/backoffice/v2/actions/sync` | Public             |
| `/api/v2/sync/**`             | JWT Required       |
| `/api/v2/entities/**`         | JWT Required       |

---

## Queue System

The application uses an in-memory priority queue system for event processing:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              QUEUE SERVICE                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
          ┌─────────────────────────┴─────────────────────────┐
          │                                                   │
          ▼                                                   ▼
┌───────────────────────────┐                    ┌───────────────────────────┐
│   PUBLISH EVENTS QUEUE    │                    │  SUBSCRIBE EVENTS QUEUE   │
│   (Broker → Blockchain)   │                    │  (Blockchain → Broker)    │
│                           │                    │                           │
│   - BrokerNotification    │                    │   - BlockchainNotification│
│   - Priority-based        │                    │   - Priority-based        │
│   - Reactive stream       │                    │   - Reactive stream       │
└───────────────────────────┘                    └───────────────────────────┘
          │                                                   │
          ▼                                                   ▼
┌───────────────────────────┐                    ┌───────────────────────────┐
│   Publish Workflow        │                    │   Subscribe Workflow      │
│   (continuous processing) │                    │   (continuous processing) │
└───────────────────────────┘                    └───────────────────────────┘
```

### Queue Operations

- `enqueueEvent(EventQueue)`: Add event to queue
- `getEventStream()`: Get reactive stream of events
- `pause()`: Pause queue processing
- `resume()`: Resume queue processing

---

## Error Handling

### Retry Strategy

The application uses Spring Retry for resilient operations:

```
@Retryable(
    retryFor = RequestErrorException.class, 
    maxAttempts = 4, 
    backoff = @Backoff(delay = 2000)
)
```

- **Max Attempts**: 4
- **Backoff Delay**: 2 seconds
- **Retry Conditions**: `RequestErrorException`

### Fatal error

If critical initialization fails (Broker/Blockchain subscription), the application terminates gracefully:

```java
private void finishApplication(String step, Throwable error) {
    log.error("Error in {}: {}", step, error.getMessage());
    SpringApplication.exit(context);
    System.exit(exitCode);
}
```

---

## Summary Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           DESMOS API OVERVIEW                                │
└─────────────────────────────────────────────────────────────────────────────┘

                    ┌───────────────────────────────┐
                    │       EXTERNAL NODES          │
                    │   (Other Access Nodes/DOME)   │
                    └───────────────────────────────┘
                                   │
                                   │ P2P Sync (Discovery + Transfer)
                                   ▼
┌─────────────┐           ┌───────────────────┐           ┌─────────────┐
│   CONTEXT   │◀─────────▶│    DESMOS API     │◀─────────▶│    DLT      │
│   BROKER    │           │                   │           │   ADAPTER   │
│  (Scorpio)  │           │  ┌─────────────┐  │           │ (DigitelTS) │
│             │  Notify   │  │   Queues    │  │  Notify   │             │
│  - Entities │──────────▶│  │ Pub │ Sub   │  │◀──────────│  - Events   │
│  - NGSI-LD  │           │  └─────────────┘  │           │  - Blocks   │
│             │◀──────────│                   │──────────▶│             │
│             │  Upsert   │  ┌─────────────┐  │  Publish  │             │
└─────────────┘           │  │ Audit DB    │  │           └─────────────┘
                          │  │ (PostgreSQL)│  │
                          │  └─────────────┘  │
                          └───────────────────┘
```
