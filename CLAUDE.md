# Claude Project Instructions

This backend is a Spring Boot Gradle multi-module service using hexagonal architecture and jOOQ. The five-module split described below exists — it is not aspirational.

Before changing Java, Gradle, database, or API code, use the project skill `/spring-hexagonal-jooq-gradle`. Use it together with the installed supporting skills when relevant: `/clean-architecture`, `/hexagonal-architecture-layers-java`, `/spring-boot-engineer`, `/spring-boot-3`, and `/java-21`.

Module layout:

- `:domain`: pure Java domain model, value objects, domain services, domain events, invariants.
- `:core`: application use cases, commands/results, input ports, output ports, orchestration.
- `:api`: REST controllers, web DTOs, validation, request/response mapping, exception mapping.
- `:infra`: driven adapters, jOOQ repositories, generated jOOQ sources, Flyway migrations, external clients.
- `:app`: Spring Boot application class, configuration, dependency wiring, runtime resources.

Dependency direction:

- `domain` depends on no project module and must not depend on Spring, jOOQ, JPA, JDBC, web, Azure, or persistence libraries.
- `core` may depend on `domain`; keep it framework-free unless a deliberate exception is documented.
- `api` may depend on `core` and `domain`; it must not depend on `infra`.
- `infra` may depend on `core` and `domain`; it owns jOOQ, database, cloud, and external integration details.
- `app` may depend on all modules and owns Spring Boot startup and composition.

jOOQ rules:

- Use jOOQ for new SQL persistence work.
- Keep `DSLContext`, generated jOOQ classes, SQL dialect configuration, and repository adapters inside `infra`.
- Never expose generated jOOQ records, JPA entities, or persistence DTOs from `api`, `core`, or `domain`.
- Prefer Flyway-managed PostgreSQL schema as the source of truth for code generation.

Security and consistency invariants (do not regress these):

- **Authorization reads the database, never a token claim.** Firebase's `roles` custom claim is a snapshot from when the token was minted, and revoking refresh tokens does not invalidate an ID token already issued. `DatabaseRoleJwtAuthenticationConverter` looks up `users.role` per request; the claim is an advisory mirror for clients only.
- **Never call Firebase inside a database transaction.** The two cannot share one, and a rollback cannot undo a granted claim. All Firebase mutations are driven by domain events handled `AFTER_COMMIT` in `infra.firebase.FirebaseUserMirroringListener`, which logs failures rather than rethrowing.
- **Account deletion is a soft delete.** The row is anonymised and marked `DELETED`, retaining `firebase_uid` as a tombstone so an ID token issued before the deletion is rejected instead of re-provisioning a fresh profile via the JIT path.
- **Aggregate writes are optimistically locked** on `users.version`. The sign-in path uses the narrow `UserRepository#recordSignIn` instead, so routine `/me` calls neither clobber concurrent changes nor contend on the lock.
- **`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`.** Without it the catch-all `@ExceptionHandler(Exception.class)` intercepts Spring's own client errors (malformed JSON, bad UUID, unknown route, wrong method) and reports them as 500.

Documented exceptions:

- `core` depends on `org.springframework:spring-tx` for `@Transactional` on application services. The annotation is inert metadata; transaction boundaries belong at the application layer, and the alternative (a `TransactionRunner` output port) is ceremony for identical behaviour. Nothing else from Spring is permitted in `core`, and `domain` stays completely pure. Enforced by `ModuleBoundaryTest.coreOnlyUsesSpringForTransactionBoundaries`.
- `core` application services carry no `@Service`/`@Component`. They are registered as beans in `app/config/UseCaseConfiguration`, which keeps them constructible in plain unit tests.
- `domain.shared.AggregateRoot` is an abstract base class, contrary to the "prefer composition" rule. It carries no domain behaviour, only the event buffer.

Build and infrastructure notes:

- Only `:app` applies the Spring Boot plugin; the rest are `java-library`. Both plugins are declared `apply false` in the root build, so `:app` and `:infra` apply them with a bare `id '...'` and no version.
- BOMs are imported with Gradle-native `platform()`, not `io.spring.dependency-management`. Versions live in `gradle/libs.versions.toml`; only unmanaged coordinates get entries there.
- Bumping `springBoot` in the version catalog requires bumping `jooq` to whatever the new BOM pins — the codegen plugin and the runtime must match.
- The root `build.gradle`'s `version = '...'` line must stay unindented at the top level: `.github/workflows/cd.yml` extracts it with an anchored grep.
- jOOQ generates offline from the Flyway scripts via `DDLDatabase`, which translates them to H2. PostgreSQL-only DDL must be wrapped in `-- [jooq ignore start]` / `-- [jooq ignore stop]`. The Testcontainers integration test in `:infra` is what proves the generated types match real PostgreSQL.
- Only `:app` may own `src/main/resources/application*.properties`.

For implementation changes, run `./gradlew check` before reporting completion (it needs a running Docker daemon for the Testcontainers tests). For Claude configuration-only changes, no Gradle build is required.

## Code style
- Do NOT add Javadoc comments to methods/classes unless explicitly requested.
- Do NOT add explanatory comments above obvious code (getters/setters, simple logic).
- Only comment non-obvious business logic or workarounds.