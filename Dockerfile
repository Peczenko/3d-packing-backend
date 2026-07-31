# syntax=docker/dockerfile:1

###############################################################################
# Runtime stage — slim JRE 25 image, runs as a non-root user.
#
# There is no build stage. jOOQ code generation needs a PostgreSQL container and
# a build stage has no Docker daemon, so the jar is built in CI — which already
# has a daemon for the Testcontainers integration tests — and COPYed in here.
###############################################################################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

# app/build/libs, not build/libs. The `jar` task is disabled in :app, so this glob
# matches exactly one file — otherwise app-<version>-plain.jar would match too and
# COPY would fail.
COPY app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
