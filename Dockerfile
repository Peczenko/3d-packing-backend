# syntax=docker/dockerfile:1

###############################################################################
# Build stage — Gradle runs on JDK 21 (Gradle 8.14 does not run on JDK 25),
# the foojay resolver auto-provisions the JDK 25 toolchain used for compilation.
###############################################################################
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Build scripts first, so that editing source does not invalidate the dependency
# cache layer below. `COPY gradle ./gradle` also brings gradle/libs.versions.toml,
# which every module script needs.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle gradle.properties ./
COPY domain/build.gradle ./domain/
COPY core/build.gradle ./core/
COPY api/build.gradle ./api/
COPY infra/build.gradle ./infra/
COPY app/build.gradle ./app/
RUN chmod +x gradlew

# Warm the dependency cache. Best-effort: never gate the build on it.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:dependencies --configuration runtimeClasspath > /dev/null || true

COPY domain/src ./domain/src
COPY core/src ./core/src
COPY api/src ./api/src
COPY infra/src ./infra/src
COPY app/src ./app/src

# There is no Docker daemon inside this stage, so Testcontainers-backed tests cannot
# run here — CI runs them instead. jOOQ code generation is offline (DDLDatabase parses
# the Flyway scripts), so :infra:jooqCodegen works with no database.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar -x test

###############################################################################
# Runtime stage — slim JRE 25 image, runs as a non-root user.
###############################################################################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

# app/build/libs, not build/libs. The `jar` task is disabled in :app, so this glob
# matches exactly one file — otherwise app-<version>-plain.jar would match too and
# COPY would fail.
COPY --from=build /workspace/app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
