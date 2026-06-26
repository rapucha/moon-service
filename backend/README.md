# Moon Service Backend

This is the first real backend module for Moon Service. It promotes the tested
Spring HTTP contract out of `prototypes/` while keeping caching, feeds, and
calendar exports deliberately out of scope.

## Current Scope

- Spring Boot application outside `prototypes/`.
- `GET /api/opportunities?q=Praha` as the query-shaped public lookup path.
- `POST /api/opportunities/search` using the same JSON request body as the scoring
  prototype fixture.
- Runtime city/location resolution is Open-Meteo backed through the
  backend-owned `LocationResolver` seam.
- Open-Meteo geocoding adapter code under `backend.location.openmeteo`, covered
  by saved provider JSON fixtures. It can be selected for live city/location
  lookup with `moon.location.resolver=open-meteo`.
- Shared Open-Meteo HTTP transport, retry, and failure classification code
  under `backend.openmeteo`.
- Coordinate-backed Moon/Sun window generation and scoring through the existing
  `jvm-scoring-prototype` Maven artifact.
- Open-Meteo weather forecast adapter code under `backend.weather.openmeteo`,
  covered by saved provider JSON fixtures. It can be selected for live hourly
  forecast lookup with `moon.weather.provider=open-meteo`.
- HTTP `400` error mapping for malformed JSON and invalid opportunity search
  requests.

This module intentionally does not yet include persistence, weather caching,
accounts, cookies, feeds, calendar generation, or deployment configuration.
Missing or unknown `moon.location.resolver` or `moon.weather.provider` values
fail startup; the runtime backend does not include fixture provider modes.

## Query Endpoint

```http
GET /api/opportunities?q=Praha
```

The location resolver and weather provider must be configured explicitly:

```bash
mvn spring-boot:run -pl backend -am \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo"
```

With that setting, the same query endpoint uses Open-Meteo Geocoding for
location resolution and can return resolved, ambiguous, not found, or
temporarily unavailable location states from the provider path. A resolved city
uses its backend location ID, provider ID, coordinates, elevation, timezone, and
country code for opportunity generation and hourly weather lookup.

## Open-Meteo Geocoding Adapter

`OpenMeteoGeocodingClient` implements the backend `LocationResolver` seam and
can be selected with `moon.location.resolver=open-meteo`. It builds encoded
Open-Meteo Geocoding requests, parses provider-shaped JSON, maps single results
to resolved locations, multiple results to ambiguous candidates, empty results
to not found, and malformed/provider failure states to temporarily unavailable.
Live no-match responses have also been observed as `generationtime_ms` with no
`results` field; that provider-shaped response maps to not found, while an
arbitrary empty object or malformed `results` shape still maps to temporarily
unavailable.
The shared Open-Meteo `RestClient` transport classifies rate limits, transient
HTTP failures, non-retryable HTTP failures, IO failures, and timeouts as typed
provider exceptions. A shared retrying transport decorator uses Spring
`RetryTemplate` with a narrow provider retry policy: at most one retry for HTTP
`429`, `502`, `503`, `504`, timeout, or IO failure. It avoids retries for
non-retryable HTTP statuses, malformed provider payloads, blank response
bodies, and valid empty results. Short `Retry-After` values are honored before
retrying; long `Retry-After` delays fail fast.
Resolved locations now carry a backend location ID, a structured provider
location ID, latitude, longitude, elevation, timezone, and country code. The
test suite still uses a test-only Open-Meteo resolver double that returns
saved provider-shaped locations without calling the live provider.

Manual live drift checks are kept outside Maven:

```bash
live-tests/run_live_geocoding_tests.sh
```

## Open-Meteo Weather Adapter

`OpenMeteoWeatherClient` implements the backend `WeatherForecastProvider` seam
and can be selected with `moon.weather.provider=open-meteo`. It requests hourly
cloud cover, low/mid/high cloud cover, precipitation probability,
precipitation amount, weather code, and visibility from Open-Meteo Forecast.
The adapter normalizes provider-shaped hourly records into backend weather
facts used by the scoring model. Malformed payloads, empty responses, HTTP
failures, IO failures, timeouts, and rate limits fail the dependency boundary
instead of producing fake no-op opportunities.
The weather client uses the same shared Open-Meteo `RestClient` transport and
Spring `RetryTemplate` wrapper as geocoding: at most one retry on HTTP `429`,
`502`, `503`, `504`, timeout, or IO failure. Short `Retry-After` values are
honored before retrying; long retry delays fail fast.

The Maven test suite uses saved provider JSON fixtures and fake weather
providers; it never calls the live weather API. Manual live weather drift checks
are kept outside Maven:

```bash
live-tests/run_live_weather_tests.sh
```

## Direct Fixture Endpoint

```http
POST /api/opportunities/search
Content-Type: application/json
```

Request body:

```json
{
  "locationId": "prague-cz",
  "start": "2026-06-29",
  "forecastHorizonDays": 7,
  "maxMoonAltitudeDegrees": 12,
  "limit": 5
}
```

Only the Prague fixture is supported for now.

This endpoint remains useful for deterministic prototype/scoring checks. Decide
later whether it stays public, becomes internal, or is removed after the
query-shaped flow covers the needed behavior.

## Verify

```bash
mvn test -pl backend -am
```

## Container Smoke Test

The repository includes `backend/Dockerfile` for packaging the backend runtime.
It uses a Maven/JDK 25 builder stage and a Java 25 JRE runtime stage; Maven is
not present in the final image.

Run the opt-in containerized live smoke check with:

```bash
live-tests/run_container_smoke_tests.sh
```

The script builds the image, starts the container on a random localhost port
with Open-Meteo geocoding and weather enabled, waits for the app to listen,
calls `GET /api/opportunities?q=Zakopane`, verifies required API fields, writes
an HTML report under `live-tests/reports/`, and removes the container.

This smoke check is intentionally outside Maven because it requires Docker,
network access, and live providers.

## Run Locally

```bash
mvn spring-boot:run -pl backend -am \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo"
```

Use those same two program arguments when starting the application from an IDE.
The backend intentionally fails startup when provider choices are missing so a
local or deployed process does not silently choose a live provider mode.

Then post the request body above to
`http://localhost:8080/api/opportunities/search`.

Or query the live lookup path:

```bash
curl 'http://localhost:8080/api/opportunities?q=Springfield'
```

`Springfield` is useful for manually checking ambiguity handling. A query that
Open-Meteo resolves to a single location should return coordinate-backed
opportunities with live Open-Meteo hourly weather.

To exercise the direct fixture-backed scoring path manually:

```bash
curl -X POST 'http://localhost:8080/api/opportunities/search' \
  -H 'Content-Type: application/json' \
  -d '{"locationId":"prague-cz","start":"2026-06-29","forecastHorizonDays":7,"maxMoonAltitudeDegrees":12,"limit":5}'
```

Manual IntelliJ HTTP Client requests, curl examples, and a Postman collection
are available under `backend/http/`. They are review/debug tooling only, not
automated tests.
