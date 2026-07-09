#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  printf 'usage: %s IMAGE_REF EXPECTED_REVISION\n' "$0" >&2
  exit 2
fi

IMAGE_REF="$1"
EXPECTED_REVISION="$2"
EXPECTED_SOURCE="${MOON_SERVICE_EXPECTED_IMAGE_SOURCE:-https://github.com/rapucha/moon-service}"
PLATFORM="${MOON_SERVICE_CONTAINER_PLATFORM:-}"
START_TIMEOUT_SECONDS="${MOON_SERVICE_CONTAINER_START_TIMEOUT_SECONDS:-90}"
STOP_TIMEOUT_SECONDS="${MOON_SERVICE_CONTAINER_STOP_TIMEOUT_SECONDS:-35}"
CONTAINER_ID=""

cleanup() {
  status=$?
  if [[ -n "$CONTAINER_ID" ]]; then
    if [[ "$status" -ne 0 ]]; then
      docker logs "$CONTAINER_ID" >&2 || true
    fi
    docker stop --timeout "$STOP_TIMEOUT_SECONDS" "$CONTAINER_ID" >/dev/null 2>&1 || true
    docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || {
  printf 'docker is required for the container image smoke test\n' >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  printf 'python3 is required for the container image smoke test\n' >&2
  exit 1
}

run_args=(
  --detach
  --publish 127.0.0.1::8080
  --env MOON_LOCATION_RESOLVER=open-meteo
  --env MOON_WEATHER_PROVIDER=open-meteo
)
if [[ -n "$PLATFORM" ]]; then
  run_args+=(--platform "$PLATFORM")
fi

CONTAINER_ID="$(docker run "${run_args[@]}" "$IMAGE_REF")"
PORT_MAPPING="$(docker port "$CONTAINER_ID" 8080/tcp)"
HOST_PORT="${PORT_MAPPING##*:}"
BASE_URL="http://127.0.0.1:$HOST_PORT"

deadline=$((SECONDS + START_TIMEOUT_SECONDS))
health_status="starting"
while ((SECONDS < deadline)); do
  container_status="$(docker inspect --format '{{.State.Status}}' "$CONTAINER_ID")"
  if [[ "$container_status" == "exited" || "$container_status" == "dead" ]]; then
    printf 'container exited during startup (state: %s)\n' "$container_status" >&2
    exit 1
  fi
  health_status="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER_ID")"
  if [[ "$health_status" == "healthy" ]]; then
    break
  fi
  if [[ "$health_status" == "unhealthy" ]]; then
    printf 'container became unhealthy during startup\n' >&2
    exit 1
  fi
  sleep 1
done

if [[ "$health_status" != "healthy" ]]; then
  printf 'container did not become healthy within %ss (last status: %s)\n' \
    "$START_TIMEOUT_SECONDS" "$health_status" >&2
  exit 1
fi

actual_revision_label="$(docker inspect \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  "$CONTAINER_ID")"
if [[ "$actual_revision_label" != "$EXPECTED_REVISION" ]]; then
  printf 'container revision label must be %s, got %s\n' \
    "$EXPECTED_REVISION" "$actual_revision_label" >&2
  exit 1
fi

actual_source_label="$(docker inspect \
  --format '{{ index .Config.Labels "org.opencontainers.image.source" }}' \
  "$CONTAINER_ID")"
if [[ "$actual_source_label" != "$EXPECTED_SOURCE" ]]; then
  printf 'container source label must be %s, got %s\n' \
    "$EXPECTED_SOURCE" "$actual_source_label" >&2
  exit 1
fi

runtime_uid="$(docker exec "$CONTAINER_ID" id -u)"
if [[ "$runtime_uid" != "10001" ]]; then
  printf 'container runtime UID must be 10001, got %s\n' "$runtime_uid" >&2
  exit 1
fi

python3 - "$BASE_URL" "$EXPECTED_REVISION" <<'PY'
import json
import sys
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

base_url = sys.argv[1]
expected_revision = sys.argv[2]

try:
    request = Request(
        base_url + "/readyz",
        headers={"User-Agent": "moon-service-image-smoke/0.1"},
    )
    with urlopen(request, timeout=3) as response:
        payload = json.loads(response.read().decode("utf-8"))
except (HTTPError, URLError, HTTPException, TimeoutError, ConnectionError) as ex:
    raise SystemExit(f"backend readiness request failed: {ex}") from ex

expected_payload = {"status": "ok", "revision": expected_revision}
if payload != expected_payload:
    raise SystemExit(f"unexpected readiness payload: {payload}")
PY

printf 'Container image smoke passed for %s (%s)\n' \
  "$IMAGE_REF" "${PLATFORM:-native platform}"
