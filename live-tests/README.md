# Moon Service Live Tests

These tests call external providers and are not part of the normal Maven or
unit-test loop. Use them manually to detect provider behavior drift.

## Why These Tests Exist

Moon Service's backend contract should be proven by deterministic tests with
fixtures. Those tests answer:

```text
Given this provider-shaped response, does Moon Service behave correctly?
```

Live provider checks answer a different question:

```text
Did an external provider change behavior in a way that affects our assumptions?
```

Containerized smoke checks answer a third question:

```text
Can the packaged backend boot and serve live provider-backed API responses?
```

Open-Meteo Geocoding is currently the first geocoder candidate. The product and
backend docs rely on observed behavior such as:

- `Prague` returns enough candidates to require disambiguation.
- `Praha` resolves to the Prague/Czech Republic candidate.
- Some native-script city names, such as `東京` and `서울`, do not resolve
  directly and therefore need curated alias fallback.
- One-character place names such as `Å` and `Y` need stricter handling because
  broad fuzzy lookup is ambiguous and provider behavior is limited.

Open-Meteo Weather is currently the first weather provider candidate. The
backend weather adapter relies on observed behavior such as:

- Hourly forecast responses include total, low, mid, and high cloud cover.
- Hourly forecast responses include precipitation probability, precipitation
  amount, weather code, and visibility.
- Requested hourly fields return arrays aligned with the `hourly.time` array.
- `timeformat=unixtime` returns hourly timestamps as Unix seconds.

These checks are intentionally broad. They should catch provider drift,
response-shape changes, rate-limit problems, changed native-script support, or
missing hourly weather fields without pretending that provider ranking, display
names, or exact forecasts are stable product contracts.

The container smoke check uses the same live providers through the running app.
It verifies runtime packaging and endpoint behavior, not exact forecast values.

## Why Python And Pytest

These tests are not exercising Java application code. They are external-provider
smoke checks around a public HTTP API, so keeping them outside Maven avoids
extra project-model plumbing for a test suite that should not run in the normal
backend build.

Pytest is used because it gives a small, readable harness for parametrized live
checks, explicit markers, skip guards, and clear failure output. Python 3.12 is
available in the development environment and is supported long enough for this
project's current MVP horizon. If these checks later need to reuse Java provider
adapters or run as deployment integration tests, they can be moved to a Maven
Failsafe/JUnit setup without changing the backend contract.

## When To Run Them

Run these checks when provider assumptions matter:

- Before implementing or changing the Open-Meteo geocoding adapter.
- Before implementing or changing the Open-Meteo weather adapter.
- When updating geocoding fixtures, aliases, or provider-mapping code.
- When updating weather fixtures or weather field mapping code.
- Before a public alpha or after changing provider configuration.
- After changing Docker packaging, runtime Java images, or application startup
  properties.
- On a manual schedule if Open-Meteo behavior drift would affect product
  confidence.
- When investigating user reports that location lookup behaves differently from
  the documented examples.

Do not run them as part of ordinary backend validation, normal CI, or every code
edit. They call the live provider, depend on network availability, can consume
quota, and may fail because the provider changed valid data rather than because
Moon Service code is broken.

The container smoke check also requires a local Docker daemon.

## Run With Virtualenv And HTML Report

Use the helper script to create or reuse a local virtual environment, install
the live-test dependencies, run one Open-Meteo drift check, and write a
self-contained HTML report:

```bash
live-tests/run_live_geocoding_tests.sh
live-tests/run_live_weather_tests.sh
live-tests/run_container_smoke_tests.sh
```

Default paths:

```text
virtualenv: live-tests/.venv/
reports:    live-tests/reports/openmeteo-geocoding.html
            live-tests/reports/openmeteo-weather.html
            live-tests/reports/container-smoke.html
```

The script accepts extra pytest arguments after its own defaults:

```bash
live-tests/run_live_geocoding_tests.sh -k Prague
live-tests/run_live_weather_tests.sh -k Amsterdam
live-tests/run_container_smoke_tests.sh -k containerized
```

Override paths if needed:

```bash
MOON_SERVICE_LIVE_TEST_VENV=/tmp/moon-live-venv \
MOON_SERVICE_LIVE_TEST_REPORT=/tmp/openmeteo-geocoding.html \
  live-tests/run_live_geocoding_tests.sh

MOON_SERVICE_LIVE_TEST_VENV=/tmp/moon-live-venv \
MOON_SERVICE_LIVE_TEST_REPORT=/tmp/openmeteo-weather.html \
  live-tests/run_live_weather_tests.sh

MOON_SERVICE_LIVE_TEST_VENV=/tmp/moon-live-venv \
MOON_SERVICE_LIVE_TEST_REPORT=/tmp/container-smoke.html \
MOON_SERVICE_CONTAINER_SMOKE_IMAGE=moon-service-backend:test \
  live-tests/run_container_smoke_tests.sh
```

## Manual Setup

Use any Python 3.12 environment with pytest installed:

```bash
python3 -m pip install -r live-tests/requirements.txt
```

## Open-Meteo Geocoding Drift Check

Run explicitly:

```bash
MOON_SERVICE_RUN_LIVE_TESTS=1 python3 -m pytest live-tests -m live_geocoding \
  --html=live-tests/reports/openmeteo-geocoding.html \
  --self-contained-html
```

Without `MOON_SERVICE_RUN_LIVE_TESTS=1`, pytest collects the tests but skips
them. Failures usually mean the live provider behavior changed and the backend
fixtures, alias table, or mapping assumptions should be reviewed; they do not
automatically mean the backend unit contract is broken.

## Open-Meteo Weather Drift Check

Run explicitly:

```bash
MOON_SERVICE_RUN_LIVE_TESTS=1 python3 -m pytest live-tests -m live_weather \
  --html=live-tests/reports/openmeteo-weather.html \
  --self-contained-html
```

Without `MOON_SERVICE_RUN_LIVE_TESTS=1`, pytest collects the tests but skips
them. Failures usually mean the live provider behavior changed and the backend
weather fixtures or mapping assumptions should be reviewed; they do not
automatically mean the backend unit contract is broken.

## Containerized Backend Smoke Check

Run explicitly:

```bash
live-tests/run_container_smoke_tests.sh
```

The script:

- builds the backend image from `backend/Dockerfile`;
- uses `maven:3.9.16-eclipse-temurin-25-noble` only as the builder stage;
- uses `eclipse-temurin:25.0.3_9-jre-noble` as the final runtime image;
- builds with a known source revision and verifies `/healthz` and `/readyz`;
- waits for the Docker health check and verifies runtime UID `10001`;
- runs the app with `moon.location.resolver=open-meteo` and
  `moon.weather.provider=open-meteo`;
- calls the containerized app at `GET /api/opportunities?q=Zakopane`;
- asserts status semantics and required response fields;
- sends a graceful stop with a timeout longer than the application's
  30-second shutdown phase, then removes the container on success or failure.

Manual pytest invocation is also possible against an already-running app:

```bash
MOON_SERVICE_RUN_CONTAINER_SMOKE=1 \
MOON_SERVICE_BASE_URL=http://127.0.0.1:8080 \
  python3 -m pytest live-tests -m container_smoke
```

Failures usually mean packaging, startup configuration, Docker runtime, live
provider availability, or API response shape should be reviewed. They do not
automatically mean the Maven fixture-backed contract is broken.
