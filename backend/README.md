# Moon Service Backend

This is the first real backend module for Moon Service. It promotes the tested
Spring HTTP contract out of `prototypes/` while keeping the implementation
fixture-backed until geocoding, weather, caching, feeds, and calendar exports
are introduced deliberately.

## Current Scope

- Spring Boot application outside `prototypes/`.
- `GET /api/opportunities?q=Praha` as the query-shaped public lookup path.
- `POST /api/opportunities/search` using the same JSON request body as the scoring
  prototype fixture.
- Fixture-backed location resolution through the backend-owned `LocationResolver`
  seam. The fixture resolver implements the same provider-facing contract later
  Open-Meteo or another geocoder should implement.
- Fixture-backed scoring through the existing `jvm-scoring-prototype` Maven
  artifact.
- HTTP `400` error mapping for malformed JSON and invalid opportunity search
  requests.

This module intentionally does not yet include persistence, live geocoding,
live weather calls, accounts, cookies, feeds, calendar generation, Docker, or
deployment configuration.

## Query Endpoint

```http
GET /api/opportunities?q=Praha
```

Current fixture behavior:

- `Praha`, `Prague`, and `prague-cz` resolve to the Prague fixture and return
  opportunity results.
- Unknown fixture queries return `status: "location_not_found"`.
- `Springfield` returns `status: "ambiguous_location"` with fixture candidates,
  proving the provider seam can represent disambiguation before live geocoding.
- The resolver contract can also represent provider outage as
  `status: "temporarily_unavailable"` with HTTP `503`; the fixture resolver
  does not currently produce that state.

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
mvn spring-boot:run -pl backend -am
```

Then post the request body above to
`http://localhost:8080/api/opportunities/search`.

Or query the fixture-backed lookup path:

```bash
curl 'http://localhost:8080/api/opportunities?q=Praha'
```
