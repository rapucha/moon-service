# Moon Service Backend

This is the first real backend module for Moon Service. It promotes the tested
Spring preview contract out of `prototypes/` while keeping the implementation
fixture-backed until geocoding, weather, caching, feeds, and calendar exports
are introduced deliberately.

## Current Scope

- Spring Boot application outside `prototypes/`.
- `POST /api/preview` using the same JSON request body as the scoring
  prototype fixture.
- Fixture-backed scoring through the existing `jvm-scoring-prototype` Maven
  artifact.
- HTTP `400` error mapping for malformed JSON and invalid preview requests.

This module intentionally does not yet include persistence, live geocoding,
live weather calls, accounts, cookies, feeds, calendar generation, Docker, or
deployment configuration.

## Endpoint

```http
POST /api/preview
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

## Verify

```bash
(cd ../prototypes/jvm-scoring && mvn install)
mvn test
```

## Run Locally

```bash
(cd ../prototypes/jvm-scoring && mvn install)
mvn spring-boot:run
```

Then post the request body above to `http://localhost:8080/api/preview`.
