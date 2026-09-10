# Replication behaviour tests — remaining scope

## Context

A previous round of work built the two-node Testcontainers harness and four passing replication
behaviour tests (see `git log` on `feat/automatic-testing`): node B runs the working tree's own
image, both nodes share a Docker network, a stub verifier issues real JWTs, and the suite runs 36
tests in ~2 minutes. What exists today covers only **happy paths**: negotiation ADD
(`P2PDataSyncBehaviorTest`), the producer audit trail (`PublishFlowBehaviorTest`), the consumer
audit trail via a DLT notification (`SubscribeFlowBehaviorTest`), and the auth chain
(`VerifierAuthChainBehaviorTest`).

Replication is the part of this system that keeps regressing (#96, #98, #111), and every
regression so far has been something *silently not happening* — an event dropped, an entity not
replicated, a check not firing. Happy-path tests cannot catch that class of bug. This round adds
the **negative and decision-path** scenarios, plus the CI wiring that makes any of it run
automatically, and removes the duplication that would otherwise be copied six more times.

Scope:

| | |
|---|---|
| Scenarios | R3 (UPDATE), R4 (SKIP), R9 (loop prevention), R10 (policy filtering), R11 (integrity, negative), R12 (trust gate, negative) |
| Deferred | R1, R5, R6, R13/R14, and **ganache + R15** — direct DLT injection already covers ~95% of the subscribe flow; the real chain adds contract deployment and timing flakiness for on-chain proof alone |
| CI | In scope — heavy suite behind label/nightly/main |

## Step 1 — Extract the shared helpers first

Do this *before* adding tests, so six new scenarios reuse it instead of multiplying the copy-paste.
Verified duplication today: the Scorpio "GET entity by id" block appears **3×** across two files;
the audit-trail read, the 90s/3s + 8s/500ms retry loop, and the broker-notification envelope each
appear **2×**.

New classes in `es.in2.desmos.support` (flat, alongside `ContainerManager`/`VerifierStub`):

- `ScorpioProbe` — `getEntity(brokerBaseUri, entityId)` / `getEntityOrNull(...)`. Collapses the 3
  duplicated `WebClient` GETs.
- `NodeBProbe` — `postDltNotification(...)`, `getEntityFromBroker(id)`, and
  `auditRowsFor(id) -> List<AuditRow(status, trader)>` (the working JDBC block already in
  `SubscribeFlowBehaviorTest`, since node B's audit table is only reachable over its mapped port).
- `ReplicationFixtures` — the broker-notification envelope, the category payloads, and
  `dltNotificationFrom(publishedAuditRecord, entityId)`.
- `ReplicationAssertions` — `assertProducerTrail(...)`, `assertConsumerTrail(...)`,
  `hasReachedPublished(...)`, `lastIsPublishedProducer(...)`.
- `QueueReadiness.untilAttemptLands(attempt, probe, success, budget)` — one implementation of the
  retry-because-the-queue-is-a-unicast-sink loop. Use Publish's semantics (on inner timeout return
  the partial probe value and let the outer poll re-test); Subscribe's `latestPublishedRecord`
  decomposes into `trailFor` + a predicate. `.ignoreExceptions()` stays opt-in per call site — it
  is required for the DLT re-POST loop (404s until the upsert) and would mask real POST failures
  in the publish loops.
- Add `ContainerManager.getNodeALocalBaseUrl()` (`http://localhost:<NODE_A_PORT>`), duplicated
  inline in two tests today. `getNodeAExternalDomain()` is **not** a substitute — that is the
  `host.testcontainers.internal` form, unresolvable from the host JVM.

Hard constraints (all verified, all load-bearing):

- **Helpers must be plain static utilities — no Spring annotations, never `@Import`ed.** Spring's
  context cache keys off the identity of the `@DynamicPropertySource` `Method` objects; a second
  customizer forks the context and the new context fails binding Netty to the fixed node-A port.
  The single `@DynamicPropertySource` stays exactly where it is, in
  `AbstractReplicationBehaviorTest`.
- Beans (`NotificationController`, `AuditRecordRepository`) are per-context — pass them in, never
  hold them in static helper state.
- **Do not** hoist `@AfterEach cleanUp` into the shared base: only the two flow tests autowire the
  repository, and truncating node A's audit table after every P2P/verifier test changes their
  state. Do not add node-B cleanup either — `P2PDataSyncBehaviorTest` seeds node B in `@BeforeAll`.
- Awaitility budgets stay caller-supplied (90s/3s, 60s/3s, 60s/2s are per-scenario).
- Drop, don't propagate: `PublishFlowBehaviorTest.postedEntityIds` (written, never read) and the
  `List.of()`-then-`.sort()` fragility (return a mutable `ArrayList`).
- `SubscribeFlowBehaviorTest`'s `data[0]` must remain Scorpio's own GET response, never a literal —
  document `brokerNotificationForCategory(...)` as publish-path-only.

Refactor the four existing tests onto the helpers and re-run: they are known-green, so any failure
is the extraction's fault, not the harness's.

## Step 2 — The two negative scenarios (cheapest, highest value)

Both extend `SubscribeFlowBehaviorTest`'s working arrangement; new classes in
`es.in2.desmos.behaviour.replication`.

**R12 — trust gate.** `BlockchainListenerServiceImpl.checkIfParticipantExistsInTrustedList` runs
*before* the RECEIVED audit write and throws `UnauthorizedDomeParticipantException`, mapped to
**401** in `GlobalExceptionHandler`. Post a DLT notification to node B with an `ethereumAddress`
that is not `ContainerManager.getTrustedDltSenderAddress()`; assert 401 **and zero audit rows** on
node B for that entity.

**R11 — integrity.** Take the working notification and tamper the `?hl=` in its `dataLocation`.
Node B then takes the "not first in chain" branch of `verifyRetrievedEntityDataIntegrity`, the
recomputed hashlink mismatches, and `HashLinkException` is raised **inside the workflow**, where
`SubscribeWorkflowImpl`'s `onErrorResume` logs and swallows it. So the POST still returns 2xx —
**assert on state, not on HTTP status**: the entity never appears in node B's broker and its audit
trail stops at RECEIVED (never RETRIEVED/PUBLISHED). Use `Awaitility.during(...)` (available,
4.2.2) for the stable-window absence check, not `atMost`.

## Step 3 — R9, loop prevention

After a replicated entity lands in node B's Scorpio, node B's *own* broker subscription fires a
notification back at node B. `BrokerListenerServiceImpl.isBrokerNotificationSelfGenerated` is
supposed to suppress it by comparing `sha256(notification dataMap)` against the `entityHash` of the
most recent RETRIEVED/DELETED audit record. Assert with `Awaitility.during` that node B accrues
**no PRODUCER rows** for that entity over a stable window.

Flag for whoever implements this: those two hashes are computed from *different serialisations* of
the same entity (Scorpio's notification payload vs. the body fetched from node A). That is exactly
the mismatch class that already bit this work once. **If this test fails, it is very likely a real
replication-loop defect, not a broken test** — report it and tag the test; do not weaken the
assertion to make it pass.

## Step 4 — R10, policy filtering

Test on the **publish path**, which is cheap and deterministic: a non-replicable entity makes
`BrokerListenerServiceImpl` return `Mono.empty()` before any audit record exists, so the assertion
is simply *zero audit rows*.

Rules, from `ReplicationPoliciesServiceImpl`: GP_1 accepts if the type is in
`LIFECYCLE_STATUS_FREE_TYPES` (`product-order`, `quote`, `usageSpecification`) **or**
`lifecycleStatus ∈ {Launched, Retired, Obsolete}`; GP_2 requires `now+2s > startDateTime` and
`now < endDateTime` (nulls pass).

Fixtures must be **written** — every existing policy fixture
(`MVEntityReplicationPoliciesInfoMother`, `MVEntity4DataNegotiationMother.sampleActive/…`) is an MV
*projection* record, usable only for unit-testing the policy service, not seedable as NGSI-LD. Add
to `ReplicationFixtures`, templated off `Instant.now()` (not hardcoded dates, unlike `EntityMother`):
rejected-by-lifecycleStatus, expired `validFor.endDateTime`, future `validFor.startDateTime`, and
an accepted `quote`/`usageSpecification` with **no** `lifecycleStatus` (the free-type case — no
NGSI-LD fixture for this exists anywhere today). Reuse `EntityMother.PRODUCT_OFFERING_1_NULL_LIFECYCLESTATUS`
as a fourth rejection case (its type is not free), but note it shares an id with `PRODUCT_OFFERING_1`
and the two cannot coexist in one broker.

Payload shape matters: `createMVEntityReplicationPoliciesInfo` reads `lifecycleStatus` as a nested
map and takes `.get("value")`, so notification payloads need the Property-wrapped form.

## Step 5 — R3 and R4, negotiation UPDATE and SKIP

Extend the P2P path. Fixtures already exist as seedable NGSI-LD JSON and need no new code:

- version bump: `EntityMother.PRODUCT_OFFERING_2` (1.2) vs `PRODUCT_OFFERING_2_OLD` (1.0) — same id
- lastUpdate tiebreak: `PRODUCT_OFFERING_3` (2024) vs `PRODUCT_OFFERING_3_OLD` (2020) — same id, same version

**R3 (UPDATE):** seed the OLD variant on node A and the NEW on node B, trigger A's
`/backoffice/v2/actions/sync`, assert A's copy is replaced by B's (both the version-wins and the
lastUpdate-tiebreak pair). **R4 (SKIP):** invert — NEW on A, OLD on B — and assert A's copy is
unchanged **and** that zero CONSUMER rows were written for it (`Awaitility.during`).

No upsert helper is needed: seeding a different variant per broker is enough. Missing audit records
on the seeded side are fine — `P2PDataSyncBehaviorTest` already proves discovery works without them.

## Step 6 — CI

`.github/workflows/build.yml` today runs `./gradlew build sonar --info` on every PR and push. Since
`integrationTest` is deliberately not wired into `check`, the new suite currently runs **nowhere**.

- **build.yml**: keep PRs on the fast lane (unit only). On push to `main`, run
  `./gradlew build integrationTest jacocoTestReport sonar` so Sonar sees combined coverage (the
  jacoco exec-merge and `sonar.tests` wiring is already in `build.gradle`). Drop `--info` — it buries
  container failures. Add `concurrency: cancel-in-progress` for PRs.
- **New `.github/workflows/integration-tests.yml`**: nightly cron, `workflow_dispatch` (with a
  `desmos_image` input), and PRs carrying a `run-integration-tests` label. `timeout-minutes: 45`.
  Authenticate to Docker Hub with the **existing** `secrets.DOCKER_USERNAME` / `secrets.DOCKER_TOKEN`
  (already used by `release.yml`) to avoid anonymous pull rate limits, prefetch the five images, and
  dump `docker ps -a` + per-container logs on failure.

## Verification

```bash
./gradlew test                                   # unit only, no Docker, ~40s — must stay unaffected
./gradlew integrationTest --tests '*ReplicationTrustGate*'   # per-scenario while iterating
./gradlew integrationTest                        # full suite, ~2 min warm
./gradlew integrationTest --rerun                # run twice: these tests are timing-sensitive
```

Each new scenario must be run **at least twice** before being called done — the retry/absence
assertions are exactly the kind that pass once by luck. Note the suite has been observed to fail
sporadically under CI/sandbox resource limits with Postgres `FATAL: sorry, too many clients already`
(many `@SpringBootTest` contexts, each with its own R2DBC pool); if that appears, it is an
environment limit, not a test regression — confirm by rerunning.

Known deferred, worth tracking separately: ganache + `dome-contract-deploy` (R15 / organic
publish→subscribe), R1, R5, R6, R13/R14, and the `ApplicationRunner` robustness gap already
documented in `ContainerManager` (a failed *initial* P2P sync permanently disables both queue
consumers, with no recovery path).
