FROM golang:1.26.5-bookworm AS build
WORKDIR /src
COPY worker/go.mod worker/go.sum ./
RUN go mod download
COPY worker/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/packing-worker ./cmd/packing-worker \
 && CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/packer ./cmd/fake-packer

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/packing-worker /usr/local/bin/packing-worker
COPY --from=build /out/packer /usr/local/bin/packer
ENTRYPOINT ["/usr/local/bin/packing-worker"]
