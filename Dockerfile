# syntax=docker/dockerfile:1

###############################################################################
# Build stage — Gradle runs on JDK 21 (Gradle 8.14 does not run on JDK 25),
# the foojay resolver auto-provisions the JDK 25 toolchain used for compilation.
###############################################################################
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
COPY src ./src

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon clean bootJar -x test

###############################################################################
# Runtime stage — slim JRE 25 image, runs as a non-root user.
###############################################################################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
