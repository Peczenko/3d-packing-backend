ARG GLTFPACK_VERSION=v1.3
ARG GLTFPACK_SHA256=0666d9dc40d60fe5b9a45f3fc24f8e6ca87112974bd5de9ca57152aa13d06017
ARG ASSIMP_VERSION=v6.0.6
ARG ASSIMP_SHA256=5f7ed412b76575621df0525a762b31da5874afff352b3a890c6308db4ac7204c

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

FROM eclipse-temurin:25-jre AS assimp-build
ARG ASSIMP_VERSION
ARG ASSIMP_SHA256
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl cmake ninja-build g++ \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL --retry 3 -o /tmp/assimp.tar.gz \
        "https://github.com/assimp/assimp/archive/refs/tags/${ASSIMP_VERSION}.tar.gz" \
 && echo "${ASSIMP_SHA256}  /tmp/assimp.tar.gz" | sha256sum -c - \
 && mkdir /src \
 && tar -xzf /tmp/assimp.tar.gz -C /src --strip-components=1
RUN cmake -S /src -B /build -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -static-libgcc" \
        -DBUILD_SHARED_LIBS=OFF \
        -DASSIMP_BUILD_ASSIMP_TOOLS=ON \
        -DASSIMP_BUILD_TESTS=OFF \
        -DASSIMP_INSTALL=OFF \
        -DASSIMP_BUILD_ZLIB=ON \
        -DASSIMP_WARNINGS_AS_ERRORS=OFF \
        -DASSIMP_IGNORE_GIT_HASH=ON \
        -DASSIMP_BUILD_USE_CCACHE=OFF \
        -DASSIMP_BUILD_ALL_IMPORTERS_BY_DEFAULT=OFF \
        -DASSIMP_BUILD_STL_IMPORTER=ON \
        -DASSIMP_BUILD_PLY_IMPORTER=ON \
        -DASSIMP_BUILD_3MF_IMPORTER=ON \
        -DASSIMP_BUILD_GLTF_IMPORTER=ON \
        -DASSIMP_BUILD_ALL_EXPORTERS_BY_DEFAULT=OFF \
        -DASSIMP_BUILD_OBJ_EXPORTER=ON \
        -DASSIMP_BUILD_ASSBIN_EXPORTER=ON \
        -DASSIMP_BUILD_ASSXML_EXPORTER=ON
RUN cmake --build /build --target assimp_cmd \
 && strip /build/bin/assimp

FROM scratch AS assimp
COPY --from=assimp-build /build/bin/assimp /assimp

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=gltfpack /out/gltfpack /usr/local/bin/gltfpack
COPY --from=assimp /assimp /usr/local/bin/assimp
RUN ["/usr/local/bin/gltfpack", "-v"]
RUN ["/usr/local/bin/assimp", "version"]
USER spring:spring

COPY app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
