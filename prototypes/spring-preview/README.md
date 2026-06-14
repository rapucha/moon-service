# Spring Preview Prototype

This is a thin Spring Boot HTTP contract harness around the Maven JVM scoring
prototype. It exists to exercise the first preview request/response shape over
HTTP while staying under `prototypes/`.

It intentionally does not include production backend concerns:

- No persistence.
- No geocoding integration.
- No live weather provider calls.
- No Docker or deployment config.
- No accounts, sessions, cookies, or security layer.
- No feeds or calendar generation.

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
  "stepMinutes": 30,
  "maxMoonAltitudeDegrees": 12,
  "minScore": 50,
  "limit": 5
}
```

Only the Prague fixture is supported. Weather is still fixed fixture weather.
The endpoint returns typed response objects through Spring/Jackson while the
Maven CLI prototype keeps its existing hand-formatted JSON output.

Invalid request bodies return HTTP `400` with `status: "invalid_request"`.
The prototype currently covers malformed JSON, non-object JSON, unsupported
fixture locations, invalid start dates, and out-of-range numeric controls.

## Verify

```bash
mvn test
```

## Run Locally

```bash
mvn spring-boot:run
```

Then post the request body above to `http://localhost:8080/api/preview`.
