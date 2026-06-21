#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${MOON_SERVICE_LIVE_TEST_VENV:-"$SCRIPT_DIR/.venv"}"
REPORT_DIR="${MOON_SERVICE_LIVE_TEST_REPORT_DIR:-"$SCRIPT_DIR/reports"}"
REPORT_PATH="${MOON_SERVICE_LIVE_TEST_REPORT:-"$REPORT_DIR/openmeteo-geocoding.html"}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

mkdir -p "$REPORT_DIR"

if [[ ! -d "$VENV_DIR" ]]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip
python -m pip install -r "$SCRIPT_DIR/requirements.txt"

MOON_SERVICE_RUN_LIVE_TESTS=1 python -m pytest \
  "$SCRIPT_DIR" \
  -m live_geocoding \
  --html="$REPORT_PATH" \
  --self-contained-html \
  "$@"

printf 'HTML report written to %s\n' "$REPORT_PATH"
