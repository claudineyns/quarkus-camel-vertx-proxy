#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONTAINER_NAME="echo-server"
IMAGE_NAME="echo-server:latest"
NETWORK_NAME="proxy-net"
HOST_PORT="${HOST_PORT:-8081}"
CONTAINER_PORT="${PORT:-8080}"

# Build image
podman build -t "$IMAGE_NAME" -f "$SCRIPT_DIR/echo/Containerfile" "$SCRIPT_DIR/echo"

# Remove previous instance if exists
podman rm -f "$CONTAINER_NAME" 2>/dev/null || true

# Network (idempotente)
podman network exists "$NETWORK_NAME" || podman network create "$NETWORK_NAME"

# Run
MSYS_NO_PATHCONV=1 podman run -d \
  --name     "$CONTAINER_NAME" \
  --hostname "$CONTAINER_NAME" \
  --network  "$NETWORK_NAME" \
  -p "${HOST_PORT}:${CONTAINER_PORT}" \
  -e PORT="${CONTAINER_PORT}" \
  "$IMAGE_NAME"

echo "[podman] [$CONTAINER_NAME] started"
echo "  Echo : http://localhost:${HOST_PORT}"
echo "  Net  : http://${CONTAINER_NAME}:${CONTAINER_PORT} (proxy-net)"
