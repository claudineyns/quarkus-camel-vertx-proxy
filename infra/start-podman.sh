#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE_NAME="vertx-proxy:latest"
CONTAINER_NAME="proxy-app"
NETWORK_NAME="proxy-net"
CERTS_DIR="${PROXY_CERTS_DIR:-$PROJECT_ROOT/infra/certs}"

echo "[podman] [$CONTAINER_NAME] building..."

# 1. Compile and package
cd "$PROJECT_ROOT"
mvn clean package -DskipTests

# 2. Network (idempotente)
podman network exists "$NETWORK_NAME" || podman network create "$NETWORK_NAME"

# 3. Build image
podman build -t "$IMAGE_NAME" -f Containerfile "$PROJECT_ROOT"

# 4. Remove previous instance if exists
podman rm -f "$CONTAINER_NAME" 2>/dev/null || true

# 5. Run
MSYS_NO_PATHCONV=1 podman run -d \
  --name     "$CONTAINER_NAME" \
  --hostname "$CONTAINER_NAME" \
  --network  "$NETWORK_NAME" \
  -p 8080:8080 \
  -p 8443:8443 \
  -p 9000:9000 \
  --memory   512m \
  -v "${CERTS_DIR}:/deployments/certs:ro" \
  -e QUARKUS_TLS_PROXY_TLS_KEY_STORE_PEM_0_CERT=/deployments/certs/server.crt \
  -e QUARKUS_TLS_PROXY_TLS_KEY_STORE_PEM_0_KEY=/deployments/certs/server.key \
  -e QUARKUS_LOG_CATEGORY__PROXY_ROUTE__LEVEL=DEBUG \
  -e QUARKUS_LOG_CATEGORY__PROXY_THRESHOLDS__LEVEL=DEBUG \
  -e PROXY_HTTP_CLIENT_POOL_MAX_CONNECTIONS_PER_HOST="${PROXY_HTTP_CLIENT_POOL_MAX_CONNECTIONS_PER_HOST:-5}" \
  -e PROXY_ACTIVE_REQUESTS_PER_HOST_LIMIT="${PROXY_ACTIVE_REQUESTS_PER_HOST_LIMIT:-5}" \
  -e PROXY_ACTIVE_REQUESTS_LIMIT="${PROXY_ACTIVE_REQUESTS_LIMIT:-10}" \
  -e PROXY_HTTP_CLIENT_RESPONSE_TIMEOUT_MS="${PROXY_HTTP_CLIENT_RESPONSE_TIMEOUT_MS:-120000}" \
  "$IMAGE_NAME"

echo "[podman] [$CONTAINER_NAME] started"
echo "  HTTP  : http://localhost:8080"
echo "  HTTPS : https://localhost:8443"
echo "  Health: http://localhost:9000/q/health"
