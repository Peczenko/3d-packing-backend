ARG PACKER_VERSION=v0.0.2
ARG PACKER_SHA256=e7e531ca277cdb5283c6cc47095562095cc1c74310bd8897f49b6a2c23699061

FROM golang:1.26.5-bookworm AS build
WORKDIR /src
COPY worker/go.mod worker/go.sum ./
RUN go mod download
COPY worker/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/packing-worker ./cmd/packing-worker

FROM golang:1.26.5-bookworm AS engine
ARG PACKER_VERSION
ARG PACKER_SHA256
RUN curl -fsSL --retry 3 --create-dirs -o /out/packer \
        "https://github.com/furby73/3d-packing-core/releases/download/${PACKER_VERSION}/packer-linux" \
 && echo "${PACKER_SHA256}  /out/packer" | sha256sum -c - \
 && chmod 0755 /out/packer

FROM gcr.io/distroless/cc-debian13:nonroot
ARG PACKER_VERSION
ENV PACKER_VERSION=${PACKER_VERSION}
COPY --from=build /out/packing-worker /usr/local/bin/packing-worker
COPY --from=engine /out/packer /usr/local/bin/packer
ENTRYPOINT ["/usr/local/bin/packing-worker"]
