# 3d-packing-backend

Backend of [3dpacking.dev](https://3dpacking.dev), a web platform that packs many 3D models into a 3D printer's build volume so that more parts fit into a single print.

Users upload meshes into shared projects, pick how many copies of each model they need, and start a packing job. A packing engine arranges the parts inside the build volume; the backend tracks the job, stores the packed result as a downloadable OBJ, and builds lightweight glTF previews for the browser.

## Who it's for

People who print batches of parts (hobbyists, makerspaces, small print farms) and would rather not arrange models on the build plate by hand. Filling the volume better means fewer print runs, fewer trips to clear the printer, and less time and material overall.

This repository is the API and job orchestration. The web frontend and the packing engine live in separate repositories (see [Related repositories](#related-repositories)).

## Features

- **Projects and sharing**: projects with `OWNER`, `WRITE` and `READ` members. A project can be disabled, which turns it into a read-only archive.
- **Model files**: STL, OBJ and STEP uploads go to object storage; downloads use short-lived signed URLs.
- **Packing jobs**: job input is validated against a versioned JSON Schema ([`contracts/packing-input`](contracts/packing-input)), which the frontend also uses to render its form. Jobs run asynchronously, can be cancelled, and send an email when they finish.
- **Result previews**: the packed OBJ is converted into instanced, compressed GLB files at up to three quality levels, so even very large results load quickly in the browser.
- **Authentication**: Firebase ID tokens. Roles and project access are read from the database on every request, never from token claims.
- **Operational alerts**: server errors trigger a throttled email alert.

## Built with

| Area | Technology |
| --- | --- |
| API | Java 25, Spring Boot 3.5, Spring Security (OAuth2 resource server), springdoc-openapi |
| Persistence | PostgreSQL 16, Flyway, jOOQ (code generated from the migrations) |
| Object storage | Azure Blob Storage ([Azurite](https://github.com/Azure/Azurite) locally) |
| Job orchestration | [Temporal](https://temporal.io) (Java SDK in the API, Go SDK in the worker) |
| Packing worker | Go 1.26, wrapping the packing engine's CLI |
| Previews | [gltfpack](https://github.com/zeux/meshoptimizer) |
| Email | Brevo, Thymeleaf templates |
| Auth | Firebase Authentication |
| Quality | JUnit 5, Testcontainers, ArchUnit, Checkstyle, Spotless |
| Delivery | GitHub Actions, Release Please, container images on GHCR, k3s + Flux |

## Architecture

The API is a Gradle multi-module project laid out as a hexagonal architecture:

| Module | Responsibility |
| --- | --- |
| `domain` | Aggregates, value objects, domain events and invariants. Plain Java, no framework. |
| `core` | Application services, commands and results, and output ports. |
| `api` | REST controllers, request/response DTOs, validation and error mapping. |
| `infra` | Adapters: jOOQ repositories, Flyway migrations, Blob storage, Firebase, Temporal, email. |
| `app` | Spring Boot entry point, configuration and wiring. |

Module boundaries are enforced by ArchUnit tests.

A packing job flows like this:

1. The API validates the input, stores the job as `QUEUED` and starts a Temporal workflow.
2. The Go worker (`worker/`) downloads the input meshes, runs the packing engine and uploads the result, signalling the workflow when the engine actually starts.
3. Activities served by the API write every state change (`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) to the job row in PostgreSQL. That row is the only job state the API reads.
4. After success, the API builds the result previews and emails the user who started the job.

## Running locally

Prerequisites: **JDK 21** (Gradle runs on it and downloads the Java 25 toolchain automatically) and **Docker**, which the build also needs for jOOQ code generation and Testcontainers.

```bash
cp .env.example .env
./gradlew :app:bootJar
docker compose --profile packing up --build
```

| Service | URL |
| --- | --- |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Temporal UI | http://localhost:8233 |

The Compose profiles add services on top of PostgreSQL and Azurite, which always start:

- *(none)*: dependencies only, for running the API from an IDE with the settings the `app` service sets in `docker-compose.yml`
- `app`: plus the API
- `packing`: plus Temporal and the packing worker (the full pipeline)
- `tools`: plus an rclone web UI for browsing Blob storage

Every `/api/**` endpoint expects a Firebase ID token. Set `FIREBASE_PROJECT_ID` in `.env` to your Firebase project. A service account (`FIREBASE_SERVICE_ACCOUNT_B64` with `FIREBASE_ADMIN_ENABLED=true`) is needed only for role assignment and account deletion.

## Testing

```bash
./gradlew check                  # unit, integration and architecture tests (needs Docker)
./gradlew lint                   # Spotless and Checkstyle
(cd worker && go test ./...)     # worker tests
```

## Related repositories

- [furby73/3d-packing-core](https://github.com/furby73/3d-packing-core): the packing engine. `worker/Dockerfile` downloads a pinned release binary and verifies its checksum.
- [furby73/3d-packing-frontend](https://github.com/furby73/3d-packing-frontend): the web frontend.
- [Peczenko/3d-packing-deployment](https://github.com/Peczenko/3d-packing-deployment): k3s manifests deployed with Flux.

## License

[MIT](LICENSE)
