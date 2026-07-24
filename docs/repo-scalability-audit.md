# Repository Scalability, Maintainability, Correctness, and Simplification Audit

Audit date: 2026-07-19  
Scope: current working tree on `feat/user-management`  
Production code changes: none

# Executive Summary

The repository has a sound modular-monolith foundation. Dependency direction is clean and tested, the domain is framework-free, API and infrastructure adapters are separated, PostgreSQL is correctly treated as the authorization source of truth, full aggregate saves use optimistic locking, and the current point queries are indexed. The live Gradle suite contains 114 passing tests, including real PostgreSQL Testcontainers coverage.

The most serious defect is a concurrency hole between the unversioned sign-in update and versioned aggregate saves. A `GET /users/me` request that read an active user can run after deletion commits and restore the real email into the deleted tombstone. In the reverse direction, a stale profile/role save can overwrite a newer Firebase email or login timestamp because the sign-in write does not advance the version.

The next constraint is cross-system consistency. Firebase calls are correctly registered for `AFTER_COMMIT`, so they do not run before a PostgreSQL commit; however, the events are in-memory, the calls are synchronous on the request thread, failures are swallowed after logging, and there is no durable retry or reconciliation. Role authorization in this backend remains safe because it reads PostgreSQL, but Firebase claims and account deletion can remain stale indefinitely.

The architecture does not need microservices. For the next 6–12 months, keep the five-module modular monolith, make field ownership and onboarding semantics explicit, add a small PostgreSQL-backed Firebase reconciliation mechanism, tighten deployment provenance, and add operational visibility. Simplify unused events, infra-only ports, duplicate validation declarations, an unused Azure Storage dependency, and excess single-method input ports.

No P0 issue was found. Four P1 issues require priority: deletion/sign-in concurrency, non-durable Firebase convergence, out-of-band Firebase revocation, and mutable/ungated releases.

# System Map

## Modules and dependency direction

| Module | Responsibility | Direct project dependencies | Assessment |
|---|---|---|---|
| `domain` | `User` aggregate, value objects, invariants, domain events | none | Pure Java and correctly framework-free. |
| `core` | user use cases, input/output ports, transaction boundary, `UserView` | `domain` | Inward dependency is correct. `spring-tx` is a documented annotation-only exception. |
| `api` | REST controller/DTOs, argument resolver, exception mapping | `core`, `domain` | Does not depend on `infra`. |
| `infra` | jOOQ repository, Flyway, Firebase Admin adapter/listener, Azure dependencies | `core`, `domain` | Does not depend on `api`. |
| `app` | Spring Boot entry point, security filter chain, CORS, composition | all modules | Correct composition root and only Boot module. |

Evidence: [settings.gradle](../settings.gradle#L23-L27), [core/build.gradle](../core/build.gradle#L3-L14), [api/build.gradle](../api/build.gradle#L3-L18), [infra/build.gradle](../infra/build.gradle#L10-L28), [app/build.gradle](../app/build.gradle#L8-L20), and the enforced [ModuleBoundaryTest](../app/src/test/java/com/packing/backend/app/architecture/ModuleBoundaryTest.java#L32-L108).

```text
Firebase client
    |
    | Bearer ID token
    v
app: SecurityConfig / NimbusJwtDecoder
    |-- signature, issuer, audience, timestamps, subject checks
    |
    v
app: DatabaseRoleJwtAuthenticationConverter
    |
    | indexed users.firebase_uid lookup on every authenticated request
    v
PostgreSQL role/status ----------> Spring Security authorities
                                      |
                                      v
api: UserController
    |
    v
core: UserApplicationService (@Transactional)
    |
    +--> infra: JooqUserRepository --> PostgreSQL
    |
    +--> in-memory Spring domain event
              |
              | AFTER_COMMIT, synchronous
              v
         infra: FirebaseUserMirroringListener --> Firebase Admin API
```

## Runtime and persistence boundaries

- Runtime begins at [Application.java](../app/src/main/java/com/packing/backend/app/Application.java#L16-L22), which scans all project packages.
- The single application service is registered explicitly by [UseCaseConfiguration.java](../app/src/main/java/com/packing/backend/app/config/UseCaseConfiguration.java#L22-L43).
- `UserApplicationService` is class-level transactional; authorization lookup overrides it as read-only: [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L39-L45), [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L66-L71).
- Full saves are one optimistic `INSERT ... ON CONFLICT(id) DO UPDATE ... WHERE users.version = expected`: [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L47-L86).
- Sign-in is a separate unversioned update: [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L89-L101).
- The only schema is `users`. Primary/unique constraints provide B-tree indexes for all current lookup predicates: [V1__create_users_table.sql](../infra/src/main/resources/db/migration/V1__create_users_table.sql#L8-L30).
- Firebase events are published in the transaction and handled only after commit: [SpringDomainEventPublisher.java](../infra/src/main/java/com/packing/backend/infra/shared/SpringDomainEventPublisher.java#L10-L28), [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L38-L73).

## Main request flows

| Flow | Current database/external sequence |
|---|---|
| First `GET /api/v1/users/me` | authorization `SELECT *`; use-case `SELECT *`; one or more username `EXISTS` checks; insert; unused `UserRegistered` publication |
| Existing `GET /api/v1/users/me` | authorization `SELECT *`; use-case `SELECT *`; unconditional email/timestamp `UPDATE` |
| `PATCH /api/v1/users/me` | authorization `SELECT *`; aggregate `SELECT *`; optional username `EXISTS`; versioned full save |
| `PUT /api/v1/users/{id}/role` | authorization `SELECT *`; method authorization; target `SELECT *`; versioned full save; after commit set Firebase claims then revoke refresh tokens |
| `DELETE /api/v1/users/me` | authorization `SELECT *`; aggregate `SELECT *`; versioned anonymizing save; after commit delete Firebase identity |

Routes are defined in [UserController.java](../api/src/main/java/com/packing/backend/api/user/UserController.java#L27-L79). Security defaults to authenticated for every non-actuator route in [SecurityConfig.java](../app/src/main/java/com/packing/backend/app/security/SecurityConfig.java#L48-L64).

# Confirmed Findings

Severity interpretation: P0 is an active critical compromise/data-loss condition; P1 is high-impact correctness, security, privacy, or release risk requiring priority; P2 is material but bounded risk or scale constraint; P3 is hardening, consistency, or developer-efficiency work.

## P0

No P0 issue was confirmed.

## P1

### P1-1 — Sign-in can restore PII after deletion; full saves can overwrite newer sign-in state

- **Classification:** confirmed correctness/privacy defect.
- **Problem:** `recordSignIn` updates email and timestamps by ID only, does not check status, does not participate in optimistic locking, and ignores its affected-row count. Full saves also write email and login fields from their aggregate snapshot.
- **Evidence:** the service reads then later calls the narrow write in [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L80-L108); deletion independently tombstones and saves in [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L128-L142) and [User.java](../domain/src/main/java/com/packing/backend/domain/user/User.java#L189-L210); narrow and full SQL overlap in [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L47-L101).
- **Concrete failure scenario:** A reads ACTIVE version 5 for `GET /me`. B deletes the same account, stores anonymized email/status DELETED/version 6, and commits. A then executes `UPDATE users SET email=<real token email> ... WHERE id=?`. The row remains DELETED but contains real PII again. Conversely, B can read version 5 for a profile/role change, A can write a new email/login without changing version, and B's full save still matches version 5 and restores its stale email/login.
- **Current impact:** a completed deletion's anonymization guarantee is false under an ordinary request race. Active users can also observe regressed Firebase-owned email, `updated_at`, and `last_login_at`.
- **Future impact:** more operation-specific writes will make ownership ambiguous and create additional lost-update combinations.
- **Recommended correction:** immediately add `status = ACTIVE` to `recordSignIn`, make timestamps monotonic, require exactly one affected row, and return a stale/inactive error when zero rows change. Then make field ownership coherent: ordinary full updates must preserve database email/login values, while deletion alone atomically writes the tombstone; alternatively version every sign-in, accepting greater contention and bounded retry logic. The split-field approach is smaller for the observed hot path. Use `UPDATE ... RETURNING` or reload when a response must reflect merged database state.
- **Estimated effort:** M.
- **Confidence:** high; independently validated by all three audit areas.

### P1-2 — Firebase convergence is non-durable and API success can misrepresent external state

- **Classification:** confirmed cross-system consistency defect.
- **Problem:** after-commit Firebase mutations are in-memory, synchronous, and log-only on failure. There is no replayable state, retry, or reconciliation. The controller/use-case documentation says role/deletion affects Firebase, while failures still return success.
- **Evidence:** events exist only in the aggregate buffer [AggregateRoot.java](../domain/src/main/java/com/packing/backend/domain/shared/AggregateRoot.java#L15-L33); Spring publication is process-local [SpringDomainEventPublisher.java](../infra/src/main/java/com/packing/backend/infra/shared/SpringDomainEventPublisher.java#L16-L28); failures are caught and swallowed [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L43-L73). The disabled adapter throws, but the listener catches it [UnavailableFirebaseUserDirectory.java](../infra/src/main/java/com/packing/backend/infra/firebase/UnavailableFirebaseUserDirectory.java#L16-L38), contradicting the promised 503 in [DEPLOYMENT.md](DEPLOYMENT.md#L249-L254).
- **Concrete failure scenarios:** PostgreSQL commits a role change and the process exits before the listener runs; Firebase keeps the old claim forever. Claim update succeeds but refresh-token revocation fails; existing client tokens keep stale claims. PostgreSQL commits deletion and Firebase deletion fails; the API returns 204, the local tombstone blocks this backend, but the Firebase identity remains indefinitely.
- **Current impact:** backend authorization remains safe because PostgreSQL wins, but frontend role UI can be permanently stale, account deletion is incomplete, and any other Firebase-backed consumer can observe the wrong state.
- **Future impact:** multiple instances and more external effects increase permanent divergence and request-thread exhaustion.
- **Recommended correction:** immediately add structured metrics/alerts and an operator replay/reconcile action; define role/deletion responses as “local desired state committed” rather than “Firebase completed.” For the next 6–12 months, persist a small user-scoped Firebase sync generation/status/attempt record in the same PostgreSQL transaction and run an idempotent, bounded reconciliation worker. Use a generic outbox only when multiple independent consumers, ordered history, or throughput justify it.
- **Estimated effort:** S for visibility/contract; M for a user-scoped reconciler; L for a generic outbox.
- **Confidence:** high.

### P1-2b — Blob storage has the same non-durable convergence, in both directions

*Added when the file-storage slice landed. Same class of defect as P1-2, same eventual fix;
recorded separately because the two failure directions differ.*

- **Classification:** known and accepted cross-system consistency limitation, not a regression.
- **Problem:** an upload writes to two systems that cannot share a transaction, and so does a delete.
  - **Orphaned blob on upload.** `FileApplicationService#upload` writes the blob, then inserts the row. If the insert fails, the bytes are in storage with nothing referencing them. Ordering the other way was rejected: an orphaned *row* is a user-visible broken resource (it lists, then 404s on download), whereas an orphaned blob is unreachable and invisible and costs only storage.
  - **Orphaned blob on delete.** `BlobCleanupListener` removes the bytes `AFTER_COMMIT` and logs rather than rethrows, exactly as `FirebaseUserMirroringListener` does — the request has already succeeded and the row is correct.
- **Current impact:** storage cost only. Neither case can surface a file the caller should not see, and neither can make a live file unreadable.
- **Why this one is better placed than P1-2:** deletion is a soft delete, so the `DELETED` row *is* a durable record of the intent and a sweep can retry against it. P1-2's intent lives only in an in-memory event that evaporates with the process. Upload orphans have no such record — that half is genuinely undetected until a sweep runs.
- **Recommended correction:** fold into the reconciliation worker P1-2 already calls for. The blob naming scheme was chosen to make this a single pass: every key is `files/{uuid}`, so orphans are a set difference between a container listing under `files/` and the `storage_key` column, filtered to blobs older than a safety window longer than the maximum request timeout. Unreaped deletions are the complementary scan over `status = 'DELETED'`.
- **Estimated effort:** S once the P1-2 worker exists; it is a second pass in the same job.
- **Confidence:** high.

### P1-3 — Firebase-side disable, deletion, or revocation performed out of band is not immediately enforced

- **Classification:** confirmed security gap with a bounded token-lifetime window.
- **Problem:** local JWT validation verifies cryptography and claims, then authorizes solely from PostgreSQL. If operations changes only Firebase, the local ACTIVE row remains authoritative and an already-issued token continues to work.
- **Evidence:** the local decoder and validators are configured in [SecurityConfig.java](../app/src/main/java/com/packing/backend/app/security/SecurityConfig.java#L73-L87); the converter reads only local authorization in [DatabaseRoleJwtAuthenticationConverter.java](../app/src/main/java/com/packing/backend/app/security/DatabaseRoleJwtAuthenticationConverter.java#L42-L64). No Firebase revocation/disabled-state synchronization exists. Firebase documents that ID tokens last about one hour and revocation detection requires an additional status check: [Manage Firebase user sessions](https://firebase.google.com/docs/auth/admin/manage-sessions).
- **Concrete failure scenario:** a compromised ACTIVE account has a valid ID token. An operator revokes refresh tokens, disables, or deletes the account in Firebase Console, but does not update PostgreSQL. The current token continues passing local validation and receives its database role until expiration.
- **Current impact:** incident response performed only in Firebase does not immediately remove backend access.
- **Future impact:** more operational entry points make dual-system procedures easier to execute incorrectly.
- **Recommended correction:** make a local `DISABLED` update the first mandatory step in every revocation workflow and provide a privileged local disable/revoke operation. Reconcile Firebase disabled/deleted state into PostgreSQL. Do not add a Firebase network call to every normal request unless the threat model requires sub-token-lifetime revocation; reserve live revocation checks for high-risk operations if necessary.
- **Estimated effort:** S for the safeguard/runbook; M for reconciliation.
- **Confidence:** high.

### P1-4 — Release tags, container tags, tested commits, and deployed revisions can diverge

- **Classification:** confirmed release/provenance defect; P1 when deployment is enabled, P2 while it is disabled.
- **Problem:** CI does not run on pushes to `master`, Docker skips tests, CD overwrites the semantic-version image tag on every master push, and the release workflow silently skips an existing version.
- **Evidence:** root version [build.gradle](../build.gradle#L20-L21); CI triggers [ci.yml](../.github/workflows/ci.yml#L3-L6); Docker test exclusion [Dockerfile](../Dockerfile#L33-L37); mutable image tags and deployment [cd.yml](../.github/workflows/cd.yml#L30-L56), [cd.yml](../.github/workflows/cd.yml#L58-L79); existing-version skip and branch target [release.yml](../.github/workflows/release.yml#L22-L40).
- **Concrete failure scenario:** commit A on master creates Git tag `v0.0.1` and image digest A under `:0.0.1`. Commit B reaches master without a version bump. Release skips `v0.0.1`, while CD builds B without a test gate and overwrites `:0.0.1`. Git release `v0.0.1` identifies A, the registry tag identifies B, and deployment by mutable tag is not reproducible.
- **Current impact:** rollback, incident attribution, and artifact provenance are unreliable; a direct or otherwise unverified master change can be published.
- **Future impact:** automated multi-environment promotion cannot safely reason about which code is running.
- **Recommended correction:** build an ordered exact-SHA chain: verify with `./gradlew build`, reject a duplicate semantic version on another SHA, create the release at `GITHUB_SHA`, push an immutable SHA tag, capture the digest, and deploy `image@sha256:...`. Keep `latest` only as a convenience. Restrict manual production publishing to protected master/environment controls.
- **Estimated effort:** S–M.
- **Confidence:** high.

## P2

### P2-1 — Concurrent first-use provisioning is not idempotent

- **Classification:** confirmed concurrency defect.
- **Problem:** two requests can both observe no local row, generate different UUIDs, and attempt inserts. The upsert conflicts on primary key, not `firebase_uid`; one legitimate request becomes a 409.
- **Evidence:** read-then-create flow [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L80-L95); generated IDs [User.java](../domain/src/main/java/com/packing/backend/domain/user/User.java#L83-L101); upsert conflict target and UID constraint [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L47-L78), [V1__create_users_table.sql](../infra/src/main/resources/db/migration/V1__create_users_table.sql#L26-L29).
- **Concrete failure scenario:** two browser tabs call first `GET /me` concurrently. Both see no row and choose the same username. A commits; B hits UID/email/username uniqueness and returns conflict instead of the winner's profile.
- **Current impact:** duplicate client retries can fail an operation intended to be JIT/idempotent, although stored data remains correct.
- **Future impact:** autoscaled instances make the race more frequent because no process-local coordination exists.
- **Recommended correction:** insert-on-conflict-do-nothing keyed by `firebase_uid`, then load/return the winning row. Retain bounded username collision retry.
- **Estimated effort:** S–M.
- **Confidence:** high.

### P2-2 — A missing local profile receives `ROLE_USER` before provisioning

- **Classification:** confirmed future authorization footgun; not a present unauthorized business operation.
- **Problem:** an unknown Firebase UID is authenticated with `ROLE_USER` even though only `GET /users/me` creates the local profile.
- **Evidence:** missing-row authority [DatabaseRoleJwtAuthenticationConverter.java](../app/src/main/java/com/packing/backend/app/security/DatabaseRoleJwtAuthenticationConverter.java#L42-L53); broad URL rule [SecurityConfig.java](../app/src/main/java/com/packing/backend/app/security/SecurityConfig.java#L57-L64); current routes [UserController.java](../api/src/main/java/com/packing/backend/api/user/UserController.java#L51-L79).
- **Concrete scenario:** today, GET provisions, PATCH/DELETE reach the use case and return 404, and admin role assignment returns 403, so there is no current privilege exploit. A future endpoint guarded only by `authenticated()` or `hasRole('USER')` would be reachable without an ACTIVE local aggregate.
- **Current impact:** inconsistent onboarding behavior and an unnecessary second lookup on pre-provision PATCH/DELETE.
- **Future impact:** new feature teams can accidentally bypass the documented local-profile precondition.
- **Recommended correction:** return an authenticated token with no application authorities for a missing row; explicitly allow authenticated `GET /api/v1/users/me`; require USER/ADMIN for other API routes. Keep provisioning out of the converter.
- **Estimated effort:** S.
- **Confidence:** high.

### P2-3 — `GET /users/me` is a multi-query write path and `last_login_at` does not mean login

- **Classification:** confirmed behavior and scale constraint; bottleneck severity is unmeasured.
- **Problem:** every authenticated request loads the full user row for three authorization fields. `GET /me` then loads it again and always updates email/timestamps. It also records request time as “login” on every call, not Firebase `auth_time`.
- **Evidence:** converter lookup [DatabaseRoleJwtAuthenticationConverter.java](../app/src/main/java/com/packing/backend/app/security/DatabaseRoleJwtAuthenticationConverter.java#L42-L64); authorization maps only ID/role/status [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L66-L71); repository selects every column [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L103-L122); GET mutation [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L80-L108).
- **Concrete scaling scenario:** an existing `GET /me` costs two indexed `SELECT *` operations plus one UPDATE. Repeated polling produces WAL/dead tuples and a hot user row. Two out-of-order requests can regress timestamps under current SQL.
- **Current impact:** unnecessary latency/write amplification and misleading public `lastLoginAt` semantics.
- **Future impact:** database connection, WAL, and autovacuum pressure grow with request rate; safe GET retries/prefetches have side effects.
- **Recommended correction:** select only ID/role/status for authorization; decide whether the API needs “login time” from token `auth_time` or a coarsened `last_seen_at`; update only when changed/older than a threshold. Consider an explicit idempotent provisioning/session command and a read-only GET in a future API version. Reuse request-scoped authorization data before adding a cross-request cache.
- **Estimated effort:** S for projection/rate limiting; M for API semantic change/request-context reuse.
- **Confidence:** high for query/write counts, medium for production bottleneck without load data.

### P2-4 — Email provisioning/synchronization has no explicit verified-email or freshness policy

- **Classification:** confirmed behavior; business/security impact depends intended authentication policy.
- **Problem:** `email_verified` is extracted but unused. Missing email becomes a domain 422, unverified email can be stored, and an older still-valid token can overwrite a newer email.
- **Evidence:** unused flag [AuthenticatedUser.java](../api/src/main/java/com/packing/backend/api/shared/security/AuthenticatedUser.java#L15-L30); controller omits it from the command [UserController.java](../api/src/main/java/com/packing/backend/api/user/UserController.java#L51-L55); service trusts token email [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L77-L105); `Email` documentation incorrectly assumes verification [Email.java](../domain/src/main/java/com/packing/backend/domain/user/Email.java#L8-L14).
- **Concrete scenario:** a newer token updates the email, then an older token calls `GET /me` and restores the old address. A Firebase provider/token without email reaches a GET endpoint and returns 422 instead of a deliberate auth/product-policy response.
- **Current impact:** stale profile data and ambiguous support/error behavior.
- **Future impact:** email-based business rules could trust data the backend never required to be verified.
- **Recommended correction:** choose the policy. If email is mandatory, reject missing/unverified email at the authentication/onboarding boundary with a stable 401/403 contract. For synchronization, store a token-issued watermark, fetch authoritative Firebase state on an explicit sync, or stop changing email on every request.
- **Estimated effort:** S–M.
- **Confidence:** high for behavior, medium for business impact.

### P2-5 — Database constraints do not enforce role, status, or version invariants

- **Classification:** confirmed integrity risk; no corrupt row was observed.
- **Problem:** schema constraints cover nullability and uniqueness only. The mapper assumes role/status strings are valid enum names and version is meaningful.
- **Evidence:** migration [V1__create_users_table.sql](../infra/src/main/resources/db/migration/V1__create_users_table.sql#L8-L30); unchecked enum conversion [UserRecordMapper.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/UserRecordMapper.java#L28-L40).
- **Concrete scenario:** a manual repair, later migration, or alternate writer stores `role='SUPERUSER'`, `status='REMOVED'`, or a negative version. Every authorization read for that row throws while mapping it.
- **Current impact:** application-only enforcement is adequate while this adapter is the sole writer, but operational repairs can create request-wide failures.
- **Future impact:** additional jobs/imports increase the chance of invalid writes.
- **Recommended correction:** add named CHECK constraints for role/status values and non-negative/positive version semantics in a Flyway migration. Test both fresh migration and upgrade.
- **Estimated effort:** S.
- **Confidence:** high.

### P2-6 — Runtime deployment is single-replica/cold-started and lacks an explicit database capacity/HA contract

- **Classification:** confirmed operations/availability gap.
- **Problem:** the documented deployment sets min replicas 0 and max replicas 1, has no liveness/readiness groups or graceful shutdown settings, and does not size Hikari against a PostgreSQL connection budget. PostgreSQL provisioning/version/HA/backup/restore/network ownership is not defined.
- **Evidence:** deployment replica settings [DEPLOYMENT.md](DEPLOYMENT.md#L66-L75); only health/info exposure and local CORS default [application.properties](../app/src/main/resources/application.properties#L12-L16); Azure datasource settings [application-azure.properties](../app/src/main/resources/application-azure.properties#L1-L21); deployment only accepts an unspecified database endpoint [DEPLOYMENT.md](DEPLOYMENT.md#L143-L188). Tests use PostgreSQL 16 only [TestcontainersConfiguration.java](../infra/src/testFixtures/java/com/packing/backend/infra/TestcontainersConfiguration.java#L21-L25).
- **Concrete failure/scaling scenario:** a cold request waits for container/JVM/JWKS/database startup; the sole replica has no failover. Raising replicas later multiplies framework-default connection pools without a defined server limit. A production browser origin is blocked because the inherited CORS allow-list is only localhost unless an undocumented override is supplied.
- **Current impact:** cold starts, no application-instance redundancy, fragile browser deployment, and no verifiable restore/compatibility posture.
- **Future impact:** horizontal scaling can exhaust PostgreSQL connections before CPU is saturated.
- **Recommended correction:** document/provision the supported PostgreSQL major/tier, backups/PITR/restore drill, network and connection budget; configure explicit Hikari limits/timeouts per replica; add readiness/liveness and graceful shutdown; set min/max/rules from availability and load goals; externalize Key Vault endpoint and production CORS allow-list.
- **Estimated effort:** M.
- **Confidence:** high for configuration gaps, medium for incident likelihood.

### P2-7 — Firebase failures and core request paths are not operationally observable

- **Classification:** confirmed observability gap.
- **Problem:** actuator exists but only health/info are exposed; no structured request correlation, application metrics, tracing, Firebase retry metrics, or reconciliation dashboard exists. Firebase failures are logs only.
- **Evidence:** actuator dependency [app/build.gradle](../app/build.gradle#L17-L20); endpoint exposure [application.properties](../app/src/main/resources/application.properties#L12-L14); log-only listener [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L43-L73). Repository search found no `MeterRegistry`, `Observation`, tracer, scheduler, or retry component.
- **Concrete scenario:** Firebase deletion fails after a 204. Unless an operator notices a single error log, the orphan identity persists; there is no count, age, alert, or user-visible pending state.
- **Current impact:** consistency failures and database/auth latency cannot be quantified or reconciled.
- **Future impact:** multi-instance incidents become difficult to correlate; scaling decisions remain speculative.
- **Recommended correction:** add correlation IDs and structured logs; counters/timers for auth DB lookup, JIT conflicts, optimistic conflicts, Firebase attempts/failures/pending age, pool saturation, and request latency; define readiness/liveness groups and alerts/SLOs. Firebase should be reported degraded, not make readiness fail while PostgreSQL remains authoritative.
- **Estimated effort:** M.
- **Confidence:** high.

### P2-8 — Privilege changes have no actor/audit record

- **Classification:** confirmed security/operations gap.
- **Problem:** role assignment carries target and new role only. The authenticated administrator is not passed into the use case/event and no immutable audit record exists.
- **Evidence:** controller [UserController.java](../api/src/main/java/com/packing/backend/api/user/UserController.java#L73-L78); command [AssignUserRoleUseCase.java](../core/src/main/java/com/packing/backend/core/user/port/in/AssignUserRoleUseCase.java#L8-L14); event [UserRoleChanged.java](../domain/src/main/java/com/packing/backend/domain/user/event/UserRoleChanged.java#L14-L19).
- **Concrete scenario:** after an accidental or malicious promotion, logs/database state identify the target and final role but not which administrator initiated it.
- **Current impact:** weak incident attribution and compliance evidence.
- **Future impact:** more administrators and automated role changes make reconstruction unreliable.
- **Recommended correction:** include actor application-user ID and request/correlation ID in the command; persist a small append-only role-change audit row in the same transaction. Do not introduce event sourcing.
- **Estimated effort:** M.
- **Confidence:** high.

### P2-9 — Setting the role claim overwrites all Firebase custom claims

- **Classification:** confirmed adapter behavior; risk depends on whether this backend exclusively owns custom claims.
- **Problem:** `setCustomUserClaims` receives a map containing only `roles`. Firebase specifies that this operation overwrites the user's existing custom claims.
- **Evidence:** adapter call [FirebaseAdminUserDirectory.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseAdminUserDirectory.java#L37-L45); official behavior [Control access with custom claims](https://firebase.google.com/docs/auth/admin/custom-claims).
- **Concrete scenario:** another trusted component stores a billing/tenant claim. A role change from this service replaces the entire map with `roles` and silently removes the other claim.
- **Current impact:** none if this service has documented exclusive ownership; that ownership is not currently stated.
- **Future impact:** integration with another Firebase consumer can lose authorization metadata.
- **Recommended correction:** explicitly declare one owner for the entire custom-claims document. If ownership must be shared, define a merge/version protocol or move advisory role UI state out of custom claims; a naive read-modify-write still races.
- **Estimated effort:** S for ownership contract; M for shared ownership design.
- **Confidence:** high for overwrite behavior, medium for current exposure.

## P3

### P3-1 — Firebase token-shape checks are incomplete and fail at inconsistent layers

- **Classification:** hardening opportunity, not a practical signature bypass.
- **Problem:** subject validation checks only nonblank; domain construction later enforces 128 characters. There is no explicit Firebase `auth_time` or issued-at-in-the-past validation.
- **Evidence:** [FirebaseSubjectValidator.java](../app/src/main/java/com/packing/backend/app/security/FirebaseSubjectValidator.java#L14-L24), [FirebaseUid.java](../domain/src/main/java/com/packing/backend/domain/user/FirebaseUid.java#L14-L23), [SecurityConfig.java](../app/src/main/java/com/packing/backend/app/security/SecurityConfig.java#L81-L87). Firebase's required token shape is documented in [Verify Firebase ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens).
- **Concrete scenario:** a malformed but correctly signed token can pass initial JWT conversion and then fail as a domain/application error rather than a stable 401.
- **Current/future impact:** inconsistent client behavior and weaker defense in depth; an attacker still cannot mint a same-project Google-signed token.
- **Recommended correction:** validate Firebase-specific subject length, `iat`, and `auth_time` in the JWT validator and map failures to the authentication entry point.
- **Estimated effort:** S.
- **Confidence:** medium.

### P3-2 — Public problem responses are not uniform across filter and controller failures

- **Classification:** confirmed API contract inconsistency.
- **Problem:** controller/framework exceptions use RFC problem details with `path`, while authentication failures from the resource-server filter use the default entry point. Tests assert only the 401 status.
- **Evidence:** problem-detail handler [GlobalExceptionHandler.java](../api/src/main/java/com/packing/backend/api/shared/error/GlobalExceptionHandler.java#L26-L164); security filter [SecurityConfig.java](../app/src/main/java/com/packing/backend/app/security/SecurityConfig.java#L48-L64); limited assertion [SecurityIT.java](../app/src/test/java/com/packing/backend/app/security/SecurityIT.java#L40-L43).
- **Concrete scenario:** malformed JSON returns a structured problem body, while a missing/invalid bearer token returns a different or empty body/header contract.
- **Current/future impact:** clients need special-case parsing and contract drift becomes likely.
- **Recommended correction:** define custom authentication entry point/access-denied handler that emits the same safe problem schema; pin content type, fields, headers, and no sensitive detail in tests.
- **Estimated effort:** S.
- **Confidence:** high.

### P3-3 — jOOQ code generation is non-incremental

- **Classification:** confirmed build-efficiency issue.
- **Problem:** `compileJava` always depends on code generation, and unchanged codegen executions are not up-to-date/cacheable with declared inputs/outputs.
- **Evidence:** jOOQ paths/configuration and task dependency [infra/build.gradle](../infra/build.gradle#L74-L149).
- **Concrete scenario:** two unchanged `:infra:jooqCodegen --no-daemon` executions both ran and took approximately 13 seconds; the final full build also executed codegen while most tasks were up-to-date.
- **Current impact:** slower local/CI feedback for every infra compilation.
- **Future impact:** migration count and generated schema size amplify the delay.
- **Recommended correction:** declare ordered migration scripts and generator/version settings as task inputs and the generated directory as output; preserve a clean-build schema-drift check.
- **Estimated effort:** S–M.
- **Confidence:** high.

## Explicit verdicts on the four pre-existing concerns

| Concern | Verdict | Evidence |
|---|---|---|
| Firebase side effects occur inside database transactions | **Refuted for rollback ordering.** Calls are `AFTER_COMMIT`, so Firebase cannot be changed and then followed by a PostgreSQL rollback. **Nuance:** listeners remain synchronous during transaction completion and block the request. | [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L12-L25), [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L43-L67) |
| Revoked/deleted Firebase accounts may retain access through locally validated JWTs | **Partially confirmed.** Locally initiated deletion is immediately blocked by the tombstone; Firebase-only disable/delete/revoke leaves local ACTIVE authorization usable until token expiry. | [DatabaseRoleJwtAuthenticationConverter.java](../app/src/main/java/com/packing/backend/app/security/DatabaseRoleJwtAuthenticationConverter.java#L55-L64), [DatabaseRoleJwtAuthenticationConverterTest.java](../app/src/test/java/com/packing/backend/app/security/DatabaseRoleJwtAuthenticationConverterTest.java#L54-L88) |
| `JooqUserRepository` can overwrite newer state with stale aggregate data | **Refuted for full-save vs full-save; confirmed for full-save vs unversioned sign-in.** The version predicate rejects two stale full saves, but `recordSignIn` changes overlapping fields without advancing version. | [JooqUserRepository.java](../infra/src/main/java/com/packing/backend/infra/persistence/user/JooqUserRepository.java#L47-L101), [JooqUserRepositoryIT.java](../infra/src/test/java/com/packing/backend/infra/persistence/user/JooqUserRepositoryIT.java#L179-L225) |
| Global catch-all converts framework client errors to 500 | **Refuted.** The advice extends `ResponseEntityExceptionHandler` and tests malformed JSON, bad UUID/enum, and wrong method. | [GlobalExceptionHandler.java](../api/src/main/java/com/packing/backend/api/shared/error/GlobalExceptionHandler.java#L33-L42), [UserControllerTest.java](../api/src/test/java/com/packing/backend/api/user/UserControllerTest.java#L188-L232) |

# Simplification Opportunities

| Opportunity | Remove, combine, or narrow | Why unnecessary/confusing now | Behavior that must remain | Maintenance benefit | Migration risk |
|---|---|---|---|---|---|
| Delete inert events | Remove `UserRegistered` and `UserProfileChanged` plus assertions. | They are published but have no production consumer; only role/deletion events drive behavior. Evidence: [User.java](../domain/src/main/java/com/packing/backend/domain/user/User.java#L83-L135), [FirebaseUserMirroringListener.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseUserMirroringListener.java#L43-L73). | Registration/profile changes and their persistence/API responses. | Fewer event contracts, allocations, tests, and false extension points. | Low. |
| Narrow Firebase abstraction ownership | Move `FirebaseUserDirectory` into `infra.firebase`; remove or relocate `ExternalServiceException` if no synchronous API use remains. | No core use case calls this port; all callers/implementations are in infra. [FirebaseUserDirectory.java](../core/src/main/java/com/packing/backend/core/user/port/out/FirebaseUserDirectory.java#L1-L31) | Firebase SDK types stay confined to infra; disabled/admin implementations remain swappable. | Core stops advertising a dependency it does not orchestrate; dead 503 mapping can disappear. | Low. |
| Combine HTTP-facing input ports | Replace four single-method user HTTP ports with one narrow `UserUseCases`/`UserFacade` contract; retain authorization lookup separately. | One controller injects four interfaces and one class implements all four. [UserController.java](../api/src/main/java/com/packing/backend/api/user/UserController.java#L31-L44), [UserApplicationService.java](../core/src/main/java/com/packing/backend/core/user/UserApplicationService.java#L39-L45) | Same transaction boundaries, commands, and controller behavior. | Fewer files/mocks/constructor parameters without coupling API to infra. | Low; defer if separate implementations are imminent. |
| Remove unused model data | Remove `firebaseUid` from `UserView`; either enforce or remove `emailVerified`. | `UserResponse` intentionally drops UID, and `emailVerified` is never used. [UserView.java](../core/src/main/java/com/packing/backend/core/user/UserView.java#L17-L40), [UserResponse.java](../api/src/main/java/com/packing/backend/api/user/UserResponse.java#L15-L37) | UID remains available inside domain/persistence; public JSON unchanged. | Smaller contracts and fewer misleading fields. | Low. |
| Remove duplicate validation literals | Reference `Username`/`User` constants from Bean Validation annotations or centralize boundary constraints. | API repeats 3/64/128 while domain owns authoritative rules. [UpdateUserProfileRequest.java](../api/src/main/java/com/packing/backend/api/user/UpdateUserProfileRequest.java#L13-L15), [Username.java](../domain/src/main/java/com/packing/backend/domain/user/Username.java#L14-L33), [User.java](../domain/src/main/java/com/packing/backend/domain/user/User.java#L30) | Field-level 400 responses and domain enforcement. | Prevents 400/422 drift after limit changes. | Low. |
| ~~Remove unused Azure Storage starter~~ **(resolved — no longer applicable)** | ~~Delete `spring-cloud-azure-starter-storage` until a storage adapter exists.~~ | **Superseded.** The file-storage slice now uses it: `infra/storage/AzureBlobBinaryStorage` implements the `BinaryStorage` output port, and `DEPLOYMENT.md` §7 is a real runbook rather than future work. The starter is a live dependency. | — | — | — |
| Rename write-hiding concepts | Rename `ResolveCurrentUser` to `ProvisionOrRefreshCurrentUser`; decide `last_seen_at` vs true token `auth_time`-based `last_login_at`. | Current names read like queries but hide insert/update behavior. | Existing HTTP behavior until a versioned API migration is chosen. | Makes transaction/query cost and semantics obvious. | Low for Java names; medium for DB/API field rename. |
| Correct drifted comments/docs | Fix Firebase claim/delete comments and replace the one-line README/stale HELP content. | Some comments say token roles are read or external failures roll back, contradicting implementation. [FirebaseAdminUserDirectory.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseAdminUserDirectory.java#L23-L27), [FirebaseAdminUserDirectory.java](../infra/src/main/java/com/packing/backend/infra/firebase/FirebaseAdminUserDirectory.java#L48-L52), [README.md](../README.md#L1) | No runtime change. | Faster onboarding and fewer security changes based on false assumptions. | Low. |

The five build modules themselves should remain. They currently provide real compile-time isolation, focused tests, and a clean composition root. Merging them would save little relative to the lost boundary enforcement. Add feature-first packages within each layer as packing features arrive; create a new build module only when ownership/build/release coupling is measured.

# Scalability Assessment

## Application instances

The application is stateless with bearer tokens and no in-memory session, so normal requests can scale horizontally. Unique constraints and optimistic locking coordinate instances. The main blockers are deployment `max-replicas=1`, per-instance database pools without an explicit total budget, and non-durable in-process Firebase events. A reconciliation worker must use PostgreSQL claiming such as `FOR UPDATE SKIP LOCKED` or equivalent leases so multiple replicas do not duplicate uncontrolled work.

## PostgreSQL and queries

Current equality predicates are correctly supported by primary/unique indexes, and there are no collection reads, pagination endpoints, N+1 loops, joins, or unbounded result sets. The present cost problem is round trips and writes, not missing indexes:

- authenticated request: one full-row UID lookup;
- existing `GET /me`: two full-row lookups plus update;
- first `GET /me`: two lookups, one or more username probes, insert;
- PATCH: two lookups, optional username probe, full save.

Before claiming a database bottleneck, seed representative data and run:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, role, status FROM users WHERE firebase_uid = ?;

EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (SELECT 1 FROM users WHERE username = ?);
```

Also capture p50/p95/p99 query time, pool wait, WAL rate, dead tuples, conflict counts, and request query budgets under concurrent `/me`, PATCH, role, and delete traffic.

## Authentication and authorization

Database-sourced role/status prevents stale custom claims from granting backend privileges and makes demotion/deletion effective on the next request. Keep that default and do not add an authorization cache until metrics show the indexed lookup materially dominates latency or database capacity. First use a narrow projection and request-scoped reuse. If a cache becomes necessary, its invalidation/SLA must explicitly define the maximum stale-demotion window.

One unavoidable request-level race remains: an admin request can load ADMIN, then be demoted by another transaction, then pass method authorization using the already-built Authentication. If sub-request-lifetime revocation is required for high-risk operations, revalidate actor authorization in the command transaction; do not impose it on all operations without that requirement.

## Cross-system consistency

PostgreSQL is the source of truth, which is the right choice. `AFTER_COMMIT` prevents rollback inversion but does not provide delivery. The pragmatic target is desired-state reconciliation keyed by user and sync generation. Role set/revoke and deletion should be retryable; duplicate Firebase deletion already treats USER_NOT_FOUND as success. Repeated refresh-token revocation is safe for security but may sign out tokens minted between an ambiguous success and retry, so record attempts/outcomes and keep retry bounds visible.

## Background work

There is no background work today. Add one bounded Firebase reconciler with:

- PostgreSQL-backed pending state written in the user transaction;
- batch size, leases/`SKIP LOCKED`, timeout, backoff, max attempts/dead-letter visibility;
- idempotent desired-state operations;
- multi-instance-safe claiming;
- metrics for pending age, attempts, outcomes, and manual replay.

Do not add a general scheduler platform, message broker, or microservice for these two low-volume effects.

## Caching

No cache is currently required. Authorization correctness intentionally spends one indexed lookup. Remove duplicate/wide reads and unnecessary writes before caching. Cache only after load evidence, and define invalidation for role/status. Profile responses may later use conditional HTTP caching/ETags when GET becomes read-only; current mutation-on-GET semantics make that unsafe.

## Observability

Health/info alone is insufficient. Add request correlation, structured JSON logs, Micrometer metrics, pool/query latency, error/problem counts, JIT and optimistic conflicts, Firebase sync state, and readiness/liveness. Add tracing only when cross-component latency cannot be diagnosed from metrics/logs; there is currently one service and one database, so a full tracing platform is optional.

## Deployment and operations

The Docker runtime is non-root and packaging is clean. Weaknesses are mutable release tags, no exact-SHA deploy gate, max one replica, hard-coded environment-specific Key Vault URL, undocumented production CORS, no explicit pool/probe/shutdown configuration, and no PostgreSQL HA/restore runbook. Address those before adding replicas.

## Team/codebase growth

The layered modules are understandable and enforced. The immediate team-growth risks are documentation drift, ports that imply false ownership, and controllers being allowed by ArchUnit to depend on any core class. Add narrower package rules if the “controllers depend only on input contracts” rule matters. Split a module/service only when a bounded context has independent ownership/release needs, the build graph becomes a measured bottleneck, or database/workload isolation is necessary—not merely because feature count grows.

# Testing Gaps

Existing coverage is strong in domain invariants, application orchestration, controller validation/error mapping, jOOQ against real PostgreSQL, sequential stale full-save rejection, tombstones, database-role converter behavior, module boundaries, and context boot. The exact missing scenarios are:

1. Two-transaction/latch test: `recordSignIn` after committed deletion affects zero rows and never restores PII.
2. Opposite lock order: sign-in first, deletion second, final row is a tombstone.
3. Full-save versus narrow sign-in: newer email/login survive a stale profile/role save.
4. Out-of-order sign-ins keep a monotonic activity timestamp.
5. Zero-row sign-in does not return a stale ACTIVE `UserView`.
6. Two simultaneous first `GET /me` requests both return the same profile.
7. Concurrent username allocation for different UIDs returns deterministic, retryable results.
8. Transaction rollback invokes no Firebase listener; commit invokes it once.
9. Process/Firebase failure after commit persists retry state and eventually converges.
10. Partial role mirror: claim set succeeds, revocation fails, retry finishes desired state.
11. Firebase delete USER_NOT_FOUND remains idempotent.
12. `firebase.admin-enabled=false` endpoint contract: reject before local mutation or return documented local-pending semantics.
13. Firebase-only disable/revoke/delete policy and local reconciliation.
14. Real bearer-filter integration with a mocked/local JWKS decoder. Current `jwt()` tests inject an already-authenticated token and bypass decoder/converter: [SecurityIT.java](../app/src/test/java/com/packing/backend/app/security/SecurityIT.java#L19-L25).
15. End-to-end stale ADMIN token with DB USER is forbidden; stale USER claim with DB ADMIN is allowed.
16. Missing, malformed, and unverified email policy.
17. Unknown-profile route matrix: GET onboarding allowed, other USER/default API routes denied.
18. Filter-chain 401/403 problem body and authenticated unknown-route 404.
19. Role-change actor/audit persistence.
20. Migration upgrade from the previous production schema, not only a fresh V1 database.
21. CHECK constraints reject invalid role/status/version.
22. Container image boot and health/readiness smoke test.
23. Deployment exact-digest/rollback test and duplicate-version failure.
24. Load/query-budget regression test using representative user volume and concurrent hot-user traffic.
25. Firebase JWKS outage, cache expiry, and key-rotation behavior.

# Target Architecture

Keep a modular monolith:

```text
app (composition/security/runtime)
  |
  +-- api (HTTP contracts)
  +-- core/domain (user and future packing feature use cases)
  +-- infra
        |-- jOOQ/PostgreSQL
        |-- Firebase adapter
        +-- bounded reconciliation worker
```

For the user feature:

1. PostgreSQL remains authoritative for role/status and desired Firebase state.
2. User/profile fields have explicit write ownership. Versioned aggregate updates own username/display name/role/status; narrow identity/activity updates own email/activity metadata; deletion atomically wins and anonymizes.
3. JIT provisioning is database-idempotent on `firebase_uid`.
4. Missing local profiles have no application role; only the explicit onboarding route is accessible.
5. Firebase desired-state generation/pending metadata is committed with role/deletion, then reconciled outside the request transaction with bounded retry and metrics.
6. Role changes write a small actor audit record.
7. API semantics distinguish local commit from asynchronous Firebase convergence.
8. Deployments verify and promote exact immutable digests.

This intentionally adds a worker and persistence metadata for demonstrated consistency requirements. It does not add Kafka, Redis, a generic event bus, or service extraction.

# Prioritized Roadmap

## Immediate correctness/security fixes

| Order | Action | Risk reduction | Effort | Dependency/complexity |
|---|---|---|---|---|
| 1 | Protect `recordSignIn` with ACTIVE predicate, affected-row check, monotonic time; stop ordinary full saves overwriting sign-in-owned fields; add both race tests. | Eliminates P1 privacy defect and P2 lost updates. | M | First; clarifies field ownership and simplifies future persistence reasoning. |
| 2 | Make concurrent JIT insert/load idempotent by `firebase_uid`. | Removes legitimate 409s across instances. | S–M | After/parallel with field ownership work. |
| 3 | Define verified/missing email policy and stop stale-token email regression. | Removes ambiguous onboarding and data trust. | S–M | Coordinate with sign-in field ownership. |
| 4 | Make local DISABLED the mandatory revocation control; add privileged operation/runbook. | Closes Firebase-only incident-response gap. | S–M | Precedes automated reconciliation. |
| 5 | Correct disabled-Firebase role/delete contract; expose pending/local-only state or reject before mutation. | Stops false 200/204/503 expectations. | S | Precedes worker/API documentation. |
| 6 | Gate CD on exact-SHA build, reject duplicate versions, tag release at SHA, deploy immutable digest. | Restores tested artifact provenance. | S–M | Independent and high priority. |

## Near-term simplifications

| Order | Action | Benefit | Effort | Complexity direction |
|---|---|---|---|---|
| 1 | Remove unused registration/profile events and assertions. | Deletes false extension points. | S | Simplifies. |
| 2 | Move infra-only Firebase strategy/exception out of core and remove dead 503 handler if unused. | Clarifies ownership and dependency contracts. | S | Simplifies. |
| 3 | Combine four HTTP-facing user ports; retain auth lookup separately. | Reduces files/mocks/wiring. | S | Simplifies. |
| 4 | Remove Azure Storage starter, unused `UserView.firebaseUid`, and unused-or-unenforced `emailVerified`. | Smaller runtime and contracts. | S | Simplifies. |
| 5 | Centralize validation constants and correct misleading names/comments/README/HELP. | Prevents drift and speeds onboarding. | S | Simplifies. |
| 6 | Make jOOQ codegen incremental/cacheable. | Cuts repeat feedback time. | S–M | Small build configuration addition. |

## Scale-readiness improvements

| Order | Action | Risk reduction | Effort | Dependency/complexity |
|---|---|---|---|---|
| 1 | Add user-scoped Firebase desired-state/pending generation and reconciler with retries/metrics/manual replay. | Durable convergence without a broker. | M | Intentionally adds justified complexity after contract is defined. |
| 2 | Add narrow auth projection, reduce duplicate `/me` read, coarsen activity writes. | Lowers query/WAL/pool cost. | S–M | Do before caching. |
| 3 | Add DB CHECK constraints and migration-upgrade tests. | Prevents invalid auth rows. | S | Independent. |
| 4 | Add role actor audit table. | Incident attribution. | M | Same transaction as role change. |
| 5 | Add structured logs, metrics, readiness/liveness, graceful shutdown, alerts/SLOs. | Makes failures and capacity measurable. | M | Required before replica scaling. |
| 6 | Define PostgreSQL major/tier/HA/backups/restore/network/connection budget; configure Hikari. | Prevents connection and recovery surprises. | M | Required before max replicas >1. |
| 7 | Raise min/max replicas and add scaling rules based on measured concurrency/latency. | Availability and throughput. | S–M | After pool/probes/visibility. |

## Changes to defer until a measurable trigger occurs

| Deferred change | Trigger |
|---|---|
| Generic transactional outbox/message broker | More independent side-effect consumers, ordered history/replay requirements, or pending throughput exceeds the simple user-state worker. |
| Redis/authorization cache | Indexed auth lookup materially dominates p95 or PostgreSQL CPU/connection budget after projection/request reuse; an explicit stale-revocation SLA exists. |
| Read replica | Read load remains the bottleneck after query reduction and pool tuning, with acceptable replica-lag semantics. |
| Partitioning/sharding | Table/index/vacuum measurements and EXPLAIN evidence show single-table limits; current point lookups do not. |
| Microservice extraction | Independent team ownership/release cadence, materially different scaling/failure domain, or unavoidable cross-context build/deploy coupling—not feature count alone. |
| Event sourcing | A regulatory/business need for reconstructable aggregate history; role audit alone needs only an append-only audit row. |
| Full distributed tracing platform | Metrics/logs cannot localize latency across newly added services/external calls. |

# Validation Log

## Commands and results

| Command/check | Result |
|---|---|
| `git status --short --branch` | Branch `feat/user-management`. Major uncommitted five-module refactor and user changes were present before the audit. They were preserved. |
| `rg --files` / `rg -n` inventories | Read all applicable repository instructions, Gradle settings/build files, README/HELP, migration, runtime properties, workflows, deployment docs, main Java sources, and test inventory. No `AGENTS.md` exists. |
| `.\gradlew.bat check --console=plain --stacktrace` | Cached run printed `BUILD SUCCESSFUL in 2s`; 24 tasks were up-to-date and jOOQ codegen executed. |
| `.\gradlew.bat check --rerun-tasks --console=plain --stacktrace` | All 25 tasks executed; Gradle printed `BUILD SUCCESSFUL in 2m` and every listed test passed. The command wrapper reached its 120-second limit immediately after final output and reported exit 124; Gradle's own final result was success. |
| Subsequent `.\gradlew.bat check --no-daemon` | `BUILD SUCCESSFUL in 16s`; no repository test/build failures. |
| `.\gradlew.bat build --console=plain --stacktrace` | `BUILD SUCCESSFUL in 4s`; `:app:bootJar` executed successfully. |
| Two unchanged `:infra:jooqCodegen --no-daemon` runs | Both executed rather than becoming up-to-date, approximately 13.7s and 13.2s. |
| Test inventory/live output | 114 tests, 0 failures/errors/skips: domain 43, core 18, API 13, infra PostgreSQL 14, app 26. |

The forced build emitted only JDK warnings about Lombok's use of terminally deprecated `sun.misc.Unsafe` methods; no test failed.

## Existing/environmental failures distinguished from findings

One subagent's concurrent Gradle invocation could not delete `app/build/test-results/test/binary/output.bin` while the primary forced check was still writing it. A later isolated check passed. This was a concurrent-build file lock, not a repository defect.

## Limitations

- No real Firebase project, Admin credentials, live JWKS rotation, or Firebase failure injection was available.
- No Azure subscription/container app/GHCR deployment was mutated or tested.
- No production PostgreSQL version, schema history, row volume, statistics, connection limit, backup, or query telemetry was available.
- No load test was run; performance severity is based on exact query/write counts and reproducible validation methods, not invented throughput numbers.
- Existing tests do not reproduce true multi-connection races; interleavings were validated from SQL predicates/transaction behavior and require the listed tests.
- Only the requested audit document was added. No production code, migration, workflow, or existing user change was modified.
