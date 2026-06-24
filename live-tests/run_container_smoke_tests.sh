#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VENV_DIR="${MOON_SERVICE_LIVE_TEST_VENV:-"$SCRIPT_DIR/.venv"}"
REPORT_DIR="${MOON_SERVICE_LIVE_TEST_REPORT_DIR:-"$SCRIPT_DIR/reports"}"
REPORT_PATH="${MOON_SERVICE_LIVE_TEST_REPORT:-"$REPORT_DIR/container-smoke.html"}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
IMAGE_TAG="${MOON_SERVICE_CONTAINER_SMOKE_IMAGE:-moon-service-backend:container-smoke}"
CONTAINER_ID=""

cleanup() {
  status=$?
  if [[ -n "$CONTAINER_ID" ]]; then
    if [[ "$status" -ne 0 ]]; then
      docker logs "$CONTAINER_ID" >&2 || true
    fi
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
  docker build -t "$IMAGE_TAG" -f "$REPO_DIR/Dockerfile" "$REPO_DIR"

CONTAINER_ID="$(docker run -d --rm \
  -p 127.0.0.1::8080 \
  "$IMAGE_TAG" \
  --moon.location.resolver=open-meteo \
  --moon.weather.provider=open-meteo)"

PORT_MAPPING="$(docker port "$CONTAINER_ID" 8080/tcp)"
HOST_PORT="${PORT_MAPPING##*:}"
BASE_URL="http://127.0.0.1:$HOST_PORT"

"$PYTHON_BIN" - "$BASE_URL" <<'PY'
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

base_url = sys.argv[1]
deadline = time.monotonic() + 60
last_error = None

while time.monotonic() < deadline:
    try:
        request = Request(base_url + "/", headers={"User-Agent": "moon-service-container-smoke/0.1"})
        with urlopen(request, timeout=2):
            sys.exit(0)
    except HTTPError as ex:
        if ex.code == 404:
            sys.exit(0)
        last_error = ex
    except URLError as ex:
        last_error = ex
    time.sleep(1)

raise SystemExit(f"backend did not become ready within 60s: {last_error}")
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
  python -m pytest \
    "$SCRIPT_DIR" \
    -m container_smoke \
    --html="$REPORT_PATH" \
    --self-contained-html \
    "$@"

printf 'Container smoke report written to %s\n' "$REPORT_PATH"
