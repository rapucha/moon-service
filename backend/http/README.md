# Backend Manual REST Requests

These files are manual review and debugging aids. They are not Maven tests and
should not be treated as CI gates because the query-shaped requests can call
live Open-Meteo services.

Start the backend with live providers:

```bash
mvn spring-boot:run -pl backend -am \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo"
```

The same requests are available in three forms:

- `local-live-opportunities.http` for IntelliJ HTTP Client.
- The curl examples below for a plain terminal.
- `postman/moon-service-local-live.postman_collection.json` for Postman import.

## Curl Examples

Resolved live lookup:

```bash
curl -sS 'http://localhost:8080/api/opportunities?q=Zakopane'
```

Ambiguous live lookup:

```bash
curl -sS 'http://localhost:8080/api/opportunities?q=Springfield'
```

Location not found:

```bash
curl -sS 'http://localhost:8080/api/opportunities?q=MoonServiceDefinitelyNotAPlace'
```

Missing query parameter:

```bash
curl -sS 'http://localhost:8080/api/opportunities'
```

Blank query parameter:

```bash
curl -sS 'http://localhost:8080/api/opportunities?q=%20%20%20'
```

Unsupported direction-mark query:

```bash
curl -sS 'http://localhost:8080/api/opportunities?q=%E2%80%8E'
```

Direct fixture-backed scoring request:

```bash
curl -sS -X POST 'http://localhost:8080/api/opportunities/search' \
  -H 'Content-Type: application/json' \
  -d '{"locationId":"prague-cz","start":"2026-06-29","forecastHorizonDays":7,"maxMoonAltitudeDegrees":12,"limit":5}'
```

The direct `POST /api/opportunities/search` path still uses the Prague scoring
fixture contract. Use `GET /api/opportunities?q=...` when validating live
geocoding and weather provider wiring through the running app.
