ARG GLTFPACK_VERSION=v1.3
ARG GLTFPACK_SHA256=0666d9dc40d60fe5b9a45f3fc24f8e6ca87112974bd5de9ca57152aa13d06017

FROM eclipse-temurin:25-jre AS gltfpack
ARG GLTFPACK_VERSION
ARG GLTFPACK_SHA256
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL --retry 3 -o /tmp/gltfpack.zip \
        "https://github.com/zeux/meshoptimizer/releases/download/${GLTFPACK_VERSION}/gltfpack-ubuntu.zip" \
 && echo "${GLTFPACK_SHA256}  /tmp/gltfpack.zip" | sha256sum -c - \
 && unzip -q /tmp/gltfpack.zip -d /out \
 && chmod 0755 /out/gltfpack

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=gltfpack /out/gltfpack /usr/local/bin/gltfpack
RUN ["/usr/local/bin/gltfpack", "-v"]
USER spring:spring

COPY app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
