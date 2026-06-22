# Multi-stage build for the Remux relay data-pipe container (runs on ECS Fargate).
# Build from the repo root:  docker build -f relay/Dockerfile -t remux-relay .

FROM golang:1.25-alpine AS build
WORKDIR /src
# GOWORK=off so we build only the root module (relay/agent/proto), ignoring the
# separate infra/lambda module that is not part of the relay image.
ENV GOWORK=off CGO_ENABLED=0
# Stamped into the binary as main.version; pass the git sha for traceability:
#   docker build --build-arg GIT_SHA=$(git rev-parse --short HEAD) ...
ARG GIT_SHA=dev
COPY go.mod go.sum ./
RUN go mod download
COPY proto/ ./proto/
COPY relay/ ./relay/
COPY agent/ ./agent/
RUN go build -trimpath -ldflags="-s -w -X main.version=${GIT_SHA}" -o /out/relay ./relay

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/relay /relay
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/relay"]
