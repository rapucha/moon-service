# Moon Service Backend

This is the first real backend module for Moon Service. It promotes the tested
Spring HTTP contract out of `prototypes/` while keeping opportunity generation
fixture-backed until weather, caching, feeds, and calendar exports are
introduced deliberately.

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
- Fixture-backed scoring through the existing `jvm-scoring-prototype` Maven
  artifact.
- HTTP `400` error mapping for malformed JSON and invalid opportunity search
  requests.

This module intentionally does not yet include persistence, live weather calls,
accounts, cookies, feeds, calendar generation, Docker, or deployment
configuration. Missing or unknown `moon.location.resolver` values fail startup;
the runtime backend does not include a fixture resolver mode.

## Query Endpoint

```http
GET /api/opportunities?q=Praha
```

The location resolver must be configured explicitly:

```bash
mvn spring-boot:run -pl backend -am -Dspring-boot.run.arguments=--moon.location.resolver=open-meteo
```

With that setting, the same query endpoint uses Open-Meteo Geocoding for
location resolution and can return resolved, ambiguous, not found, or
temporarily unavailable location states from the provider path. Opportunity
generation is still backed by the scoring prototype, so arbitrary Open-Meteo
location IDs are not yet supported by the downstream scoring step. A resolved
city whose provider ID cannot be scored yet returns `temporarily_unavailable`
rather than `invalid_request` on the query endpoint.

## Open-Meteo Geocoding Adapter

`OpenMeteoGeocodingClient` implements the backend `LocationResolver` seam and
can be selected with `moon.location.resolver=open-meteo`. It builds encoded
Open-Meteo Geocoding requests, parses provider-shaped JSON, maps single results
to resolved locations, multiple results to ambiguous candidates, empty results
to not found, and malformed/provider failure states to temporarily unavailable.
The Spring `RestClient` transport classifies rate limits, transient HTTP
failures, non-retryable HTTP failures, IO failures, and timeouts as typed
provider exceptions. A retrying transport decorator uses Spring `RetryTemplate`
with a narrow provider retry policy: at most one retry for HTTP `429`, `502`,
`503`, `504`, timeout, or IO failure. It avoids retries for non-retryable HTTP
statuses, malformed provider payloads, blank response bodies, and valid empty
results. Short `Retry-After` values are honored before retrying; long
`Retry-After` delays fail fast.
Resolved locations now carry a backend location ID, a structured provider
location ID, latitude, longitude, elevation, timezone, and country code. The
test suite still uses a test-only Open-Meteo resolver double that returns
prototype-compatible backend location IDs such as `prague-cz`.

Manual live drift checks are kept outside Maven:

```bash
live-tests/run_live_geocoding_tests.sh
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

## Run Locally

```bash
mvn spring-boot:run -pl backend -am -Dspring-boot.run.arguments=--moon.location.resolver=open-meteo
```

Then post the request body above to
`http://localhost:8080/api/opportunities/search`.

Or query the live lookup path:

```bash
curl 'http://localhost:8080/api/opportunities?q=Springfield'
```

`Springfield` is useful for manually checking ambiguity handling. A query that
Open-Meteo resolves to a single non-fixture location may still return
`temporarily_unavailable` until the opportunity engine supports arbitrary
provider-backed locations.

To exercise the direct fixture-backed scoring path manually:

```bash
curl -X POST 'http://localhost:8080/api/opportunities/search' \
  -H 'Content-Type: application/json' \
  -d '{"locationId":"prague-cz","start":"2026-06-29","forecastHorizonDays":7,"maxMoonAltitudeDegrees":12,"limit":5}'
```
