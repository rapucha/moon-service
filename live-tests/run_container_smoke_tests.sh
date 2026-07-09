#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VENV_DIR="${MOON_SERVICE_LIVE_TEST_VENV:-"$SCRIPT_DIR/.venv"}"
REPORT_DIR="${MOON_SERVICE_LIVE_TEST_REPORT_DIR:-"$SCRIPT_DIR/reports"}"
REPORT_PATH="${MOON_SERVICE_LIVE_TEST_REPORT:-"$REPORT_DIR/container-smoke.html"}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
IMAGE_TAG="${MOON_SERVICE_CONTAINER_SMOKE_IMAGE:-moon-service-backend:container-smoke}"
BUILD_REVISION="${MOON_SERVICE_CONTAINER_SMOKE_REVISION:-container-smoke}"
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
  printf 'docker is required for container smoke tests\n' >&2
  exit 1
}

mkdir -p "$REPORT_DIR"

DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-1}" \
  docker build \
    --build-arg "MOON_BUILD_REVISION=$BUILD_REVISION" \
    -t "$IMAGE_TAG" \
    -f "$REPO_DIR/backend/Dockerfile" \
    "$REPO_DIR"

CONTAINER_ID="$(docker run -d --rm \
  -p 127.0.0.1::8080 \
  "$IMAGE_TAG" \
  --moon.location.resolver=open-meteo \
  --moon.weather.provider=open-meteo)"

PORT_MAPPING="$(docker port "$CONTAINER_ID" 8080/tcp)"
HOST_PORT="${PORT_MAPPING##*:}"
BASE_URL="http://127.0.0.1:$HOST_PORT"

deadline=$((SECONDS + 60))
health_status="starting"
while ((SECONDS < deadline)); do
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
  printf 'container did not become healthy within 60s (last status: %s)\n' "$health_status" >&2
  exit 1
fi

runtime_uid="$(docker exec "$CONTAINER_ID" id -u)"
if [[ "$runtime_uid" != "10001" ]]; then
  printf 'container runtime UID must be 10001, got %s\n' "$runtime_uid" >&2
  exit 1
fi

"$PYTHON_BIN" - "$BASE_URL" "$BUILD_REVISION" <<'PY'
import json
import sys
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

base_url = sys.argv[1]
expected_revision = sys.argv[2]

try:
    request = Request(base_url + "/readyz", headers={"User-Agent": "moon-service-container-smoke/0.1"})
    with urlopen(request, timeout=3) as response:
        payload = json.loads(response.read().decode("utf-8"))
except (HTTPError, URLError, HTTPException, TimeoutError, ConnectionError) as ex:
    raise SystemExit(f"backend readiness request failed: {ex}") from ex

if payload != {"status": "ok", "revision": expected_revision}:
    raise SystemExit(f"unexpected readiness payload: {payload}")
PY

if [[ ! -d "$VENV_DIR" ]]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip
python -m pip install -r "$SCRIPT_DIR/requirements.txt"

MOON_SERVICE_RUN_CONTAINER_SMOKE=1 \
MOON_SERVICE_BASE_URL="$BASE_URL" \
MOON_SERVICE_EXPECTED_REVISION="$BUILD_REVISION" \
  python -m pytest \
    "$SCRIPT_DIR" \
    -m container_smoke \
    --html="$REPORT_PATH" \
    --self-contained-html \
    "$@"

printf 'Container smoke report written to %s\n' "$REPORT_PATH"
