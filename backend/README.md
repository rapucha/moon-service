# Moon Service Backend

This is the first real backend module for Moon Service. It promotes the tested
Spring HTTP contract out of `prototypes/` while keeping durable/shared caches,
feeds, and calendar exports deliberately out of scope.

## Current Scope

- Spring Boot application outside `prototypes/`.
- `GET /api/opportunities?q=Praha` as the query-shaped public lookup path.
- `GET /api/opportunities?locationId=moon-service-3067696` for selecting one
  backend location candidate after ambiguous city lookup.
- Browser lookup page at `/search?q=Praha`, backed by the query-shaped API.
- `POST /api/opportunities/search` using the same JSON request body as the scoring
  prototype fixture.
- Runtime city/location resolution is Open-Meteo backed through the
  backend-owned `LocationResolver` seam.
- Request-level logging, process-local provider counters, quota windows, cache
  stats, and a protected operator status endpoint are available for small test
  spikes.
- Open-Meteo geocoding adapter code under `backend.location.openmeteo`, covered
  by saved provider JSON fixtures. It can be selected for live city/location
  lookup with `moon.location.resolver=open-meteo`.
- Shared Open-Meteo HTTP transport, retry, and failure classification code
  under `backend.openmeteo`.
- In-memory provider-call protections for the Open-Meteo runtime path: repeated
  and concurrent identical geocoding/weather lookups share one upstream call
  inside a single backend process.
- Coordinate-backed Moon/Sun window generation and scoring through the existing
  `jvm-scoring-prototype` Maven artifact.
- Opportunity Moon summaries include optional observer-oriented
  `brightLimbTiltDegrees` and `northPoleTiltDegrees` data for suggested-time
  rendering. Clients retain the phase-angle and north-up texture fallbacks
  independently when either value is unavailable; lunar libration remains out
  of scope.
- Open-Meteo weather forecast adapter code under `backend.weather.openmeteo`,
  covered by saved provider JSON fixtures. It can be selected for live hourly
  forecast lookup with `moon.weather.provider=open-meteo`.
- HTTP `400` error mapping for malformed JSON and invalid opportunity search
  requests.

This module intentionally does not yet include persistence, durable/shared
caches, accounts, cookies, feeds, calendar generation, or deployment
configuration.
Missing or unknown `moon.location.resolver` or `moon.weather.provider` values
fail startup; the runtime backend does not include fixture provider modes.

## Query Endpoint

```http
GET /api/opportunities?q=Praha
```

The location resolver and weather provider must be configured explicitly:

```bash
mvn -pl backend -am spring-boot:run \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo"
```

With that setting, the same query endpoint uses Open-Meteo Geocoding for
location resolution and can return resolved, ambiguous, not found, or
temporarily unavailable location states from the provider path. A resolved city
uses its backend location ID, provider ID, coordinates, elevation, timezone, and
country code for opportunity generation and hourly weather lookup. Anonymous
query and selected-location lookups request up to ten raw ranked recommendation
windows by default. The browser groups windows from the same physical Moon pass,
so it may show fewer than ten pass cards. This broader result set is provisional
while scoring is evaluated under issue #33; the direct fixture endpoint below
continues to honor its explicit caller-supplied `limit`.

## Runtime Configuration

Provider selection remains explicit. Missing or unsupported
`moon.location.resolver` or `moon.weather.provider` values fail startup; the
runtime backend currently supports only `open-meteo` for both. The settings
below tune that Open-Meteo runtime path and keep their defaults in code, so
local runs work without an external config file after provider selection is
set. Spring binds them from command-line arguments, environment variables, or
configuration files. The checked-in `application.properties` defines only the
shared deployment lifecycle defaults: graceful shutdown and its timeout.
Duration values accept Spring duration syntax such as `3s` or ISO-8601 values
such as `PT3S`.

For the current local app, configure these values the same way as the run
commands in this README: pass command-line arguments through
`spring-boot.run.arguments`. In IntelliJ, use the same `--moon...` values in
the Spring Boot run configuration's program arguments, or set equivalent
environment variables. For example:

```bash
MOON_LOCATION_RESOLVER=open-meteo
MOON_WEATHER_PROVIDER=open-meteo
MOON_ADMIN_GENERATE_TOKEN=true
MOON_BUILD_REVISION=<git-commit-sha>
```

For alpha hosting, prefer environment variables or the hosting platform's app
configuration/secrets. The normal app configuration should only need provider
selection and the admin access setting; Open-Meteo endpoint, timeout, retry,
and cache values should stay on code defaults unless an operator has a concrete
reason to tune them.

| Property | Default | Purpose |
| --- | --- | --- |
| `moon.location.resolver` | unset | Must be `open-meteo` for runtime geocoding. |
| `moon.weather.provider` | unset | Must be `open-meteo` for runtime weather. |
| `moon.build.revision` | `local` | Safe revision identifier returned by operational health and protected admin status. Container builds set this from `MOON_BUILD_REVISION`. |
| `moon.open-meteo.timeout` | `3s` | Open-Meteo connect and read timeout. |
| `moon.open-meteo.max-transport-retries` | `1` | Maximum retries after the first Open-Meteo attempt. |
| `moon.open-meteo.max-retry-after` | `1s` | Largest provider `Retry-After` delay accepted for retry. |
| `moon.open-meteo.geocoding.endpoint` | `https://geocoding-api.open-meteo.com/v1/search` | Geocoding search endpoint. |
| `moon.open-meteo.geocoding.get-endpoint` | `https://geocoding-api.open-meteo.com/v1/get` | Geocoding lookup-by-ID endpoint. |
| `moon.open-meteo.geocoding.result-count` | `10` | Maximum geocoding candidates requested. |
| `moon.open-meteo.geocoding.language` | `en` | Open-Meteo geocoding response language. |
| `moon.open-meteo.forecast.endpoint` | `https://api.open-meteo.com/v1/forecast` | Forecast endpoint. |
| `moon.cache.geocoding.maximum-size` | `2000` | Process-local geocoding cache maximum entries. |
| `moon.cache.geocoding.resolved-ttl` | `24h` | TTL for resolved geocoding results. |
| `moon.cache.geocoding.ambiguous-ttl` | `24h` | TTL for ambiguous geocoding results. |
| `moon.cache.geocoding.not-found-ttl` | `10m` | TTL for not-found geocoding results. |
| `moon.cache.geocoding.temporarily-unavailable-ttl` | `30s` | TTL for temporarily unavailable geocoding results. |
| `moon.cache.weather.maximum-size` | `1000` | Process-local weather cache maximum entries. |
| `moon.cache.weather.available-ttl` | `1h` | TTL for successful weather forecasts. |
| `moon.cache.weather.unavailable-ttl` | `30s` | TTL for temporarily unavailable weather lookups. |
| `moon.provider-quotas.operations.<id>.provider` | default for built-in operations, otherwise unset | Provider name for an operation tracked in `/admin/status`, for example `open-meteo` or a future LLM provider. |
| `moon.provider-quotas.operations.<id>.operation` | default for built-in operations, otherwise unset | Operation name, for example `geocoding`, `weather`, or `fictional-location-resolution`. |
| `moon.provider-quotas.operations.<id>.hourly-limit` | unknown | Optional known hourly call limit. |
| `moon.provider-quotas.operations.<id>.daily-limit` | unknown | Optional known daily call limit. |
| `moon.provider-quotas.operations.<id>.monthly-limit` | unknown | Optional known monthly call limit. |

Quota limits are configuration, not code constants, so operators can switch
provider plans without rebuilding. The backend always registers
`open-meteo-geocoding` and `open-meteo-weather` with unknown limits unless
limits are configured. Extra operations can be added to the status surface
before their provider integration is wired, as long as both provider and
operation names are configured.

Example local Open-Meteo free-tier limits:

```bash
mvn -pl backend -am spring-boot:run \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.token=$ADMIN_TOKEN --moon.provider-quotas.operations.open-meteo-geocoding.hourly-limit=5000 --moon.provider-quotas.operations.open-meteo-geocoding.daily-limit=10000 --moon.provider-quotas.operations.open-meteo-weather.hourly-limit=5000 --moon.provider-quotas.operations.open-meteo-weather.daily-limit=10000"
```

Example placeholder for a later LLM-backed fictional-location fallback:

```bash
--moon.provider-quotas.operations.fictional-location-llm.provider=example-llm
--moon.provider-quotas.operations.fictional-location-llm.operation=fictional-location-resolution
--moon.provider-quotas.operations.fictional-location-llm.daily-limit=100
--moon.provider-quotas.operations.fictional-location-llm.monthly-limit=1000
```

## Browser Lookup Page

```http
GET /search?q=Praha
```

The browser page is the first anonymous MVP lookup flow. It serves static
HTML/CSS/JavaScript from the backend, calls `GET /api/opportunities?q=...` only
after an explicit form submit or a shared `/search?q=...` page load, and renders
the documented product states without exposing provider internals. The current
page is intentionally plain JavaScript with no frontend build step.
Ambiguous-location choices call the same endpoint with a selected backend
location ID, for example `GET /api/opportunities?locationId=moon-service-3067696`,
and are shareable as `/search?locationId=moon-service-3067696`.

The page keeps recent searches only in browser `localStorage` under
`moonService.recentSearches.v1`. Entries contain display name, location ID, and
timezone only; the page still works if browser storage is unavailable. It does
not create accounts, cookies, email subscriptions, or server-side user profiles.

Frontend tooling is repo-local Node tooling. Install once with:

```bash
npm install
```

Useful checks:

```bash
npm run js:check
npm run js:lint
npm run js:docs
npm run ui:smoke
npm run frontend:check
```

`js:check` runs TypeScript over the plain JavaScript files with `checkJs`
enabled, so JSDoc typedefs can catch API-shape and DOM mistakes without adding
a frontend build step. `ui:smoke` runs Playwright against desktop and mobile
viewports. By default it starts or reuses the backend on port `8081`, uses the
system Chrome at `/usr/bin/google-chrome`, and mocks the opportunity API with a
fixture so the smoke check does not depend on live providers. Override
`MOON_SERVICE_BASE_URL`, `PLAYWRIGHT_CHROME_PATH`, or
`MOON_SERVICE_PLAYWRIGHT_START_SERVER=false` when needed.

Manual browser checks for frontend behavior:

- Open `/search`, submit `Praha`, and confirm opportunities render with Moon,
  Sun/light, weather, score, share action, tooltips, and caveat details.
- Open `/about` and confirm the short service intro, purpose, and current
  boundaries are visible.
- Open `/search?q=Praha` directly and confirm the page is shareable without an
  account.
- Search `Springfield` and confirm the ambiguous-location state presents
  actionable choices that resolve through `locationId`.
- Search an unknown place and confirm the not-found state is actionable.
- Submit an empty location and confirm the validation state stays on the page.
- Narrow the browser to a mobile viewport and confirm the search form, recent
  searches, opportunity facts, and score display do not overlap or truncate
  important text.
- Clear recent searches and confirm only browser-local entries are removed.

## Open-Meteo Geocoding Adapter

`OpenMeteoGeocodingClient` implements the backend `LocationResolver` seam and
can be selected with `moon.location.resolver=open-meteo`. It builds encoded
Open-Meteo Geocoding requests, parses provider-shaped JSON, maps single results
to resolved locations, filters nearby same-city district/airport provider noise,
maps remaining multiple results to ambiguous candidates, empty results to not
found, and malformed/provider failure states to temporarily unavailable.
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
retrying; long `Retry-After` delays fail fast. The default Open-Meteo
connect/read timeout is 3 seconds so dependency slowness becomes
`temporarily_unavailable` instead of tying up request threads for a long wait.
Resolved locations now carry a backend location ID, a structured provider
location ID, latitude, longitude, elevation, timezone, and country code. The
test suite still uses a test-only Open-Meteo resolver double that returns
saved provider-shaped locations without calling the live provider.

Runtime Open-Meteo geocoding is wrapped in an in-memory Caffeine cache. The
cache key is the normalized query text, or the selected backend location ID for
`locationId` lookups. Successful resolved and ambiguous responses are cached
for 24 hours, not-found responses for 10 minutes, and
temporarily-unavailable responses for 30 seconds. Caffeine's atomic per-key
loads provide single-flight behavior, so concurrent identical cache misses
share the same upstream geocoding call.

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
honored before retrying; long retry delays fail fast. The default Open-Meteo
connect/read timeout is 3 seconds.

Runtime Open-Meteo weather lookup is wrapped in an in-memory Caffeine cache.
The cache key matches the provider request shape: coordinates rounded to 4
decimal places, elevation, UTC start/end forecast hours, and forecast horizon.
Successful forecasts are cached for 1 hour, and temporarily-unavailable weather
lookups are cached for 30 seconds. Concurrent identical cache misses share one
upstream weather call inside the process.

These caches are deliberately small, process-local MVP protections. They reduce
duplicate upstream calls during small spikes, such as several users searching
the same city at once, and dampen brief provider slowness. They are cleared on
restart and are not shared across multiple backend instances; Redis,
distributed rate limiting, durable provider counters, and autoscaling remain
out of scope for this module.

The Maven test suite uses saved provider JSON fixtures and fake weather
providers; it never calls the live weather API. Manual live weather drift checks
are kept outside Maven:

```bash
live-tests/run_live_weather_tests.sh
```

## Observability

The backend adds MVP-level observability for small public tests without storing
or logging raw user location data by default.

Every HTTP request receives an `X-Request-Id` response header. If the client
sends a safe `X-Request-Id`, the backend reuses it; otherwise it generates one.
The request log records method, path, status, duration, and request ID. It uses
the route path only, not the raw query string, so location queries such as
`q=...` are not written to application logs by this filter.

### Operational health

The deployment-facing health endpoints are unauthenticated and intentionally
small:

```http
GET /healthz
GET /readyz
```

`/healthz` returns HTTP `200` only while Spring Boot reports a correct internal
liveness state. `/readyz` returns HTTP `200` only while the application accepts
traffic, including after startup work has completed; it returns `503` while
traffic is refused during lifecycle transitions. Both return only `status` and
`revision`, set `Cache-Control: no-store`, and never call geocoding, weather, or
other external providers. The deterministic local-build revision is `local`.
Container and CI builds should set it to the source commit.

Successful `GET`/`HEAD` probes are logged at DEBUG instead of INFO so a regular
container health check does not create continuous SD-card log traffic. Failed
probes remain visible at INFO. These routes are operational endpoints, not part
of the location/opportunity product API.

The operator status endpoint is:

```http
GET /admin/status
```

Admin routes are disabled unless `moon.admin.token` is configured or the
explicit local-development generator is enabled. When neither is configured,
`/admin/**` returns `404`. When admin routes are enabled, admin requests must
send the token in the `X-Moon-Admin-Token` header; missing or wrong tokens
return `401`. This is the backend-owned MVP access boundary for operator routes
and does not introduce public-user accounts.

Example local run with an operator token:

```bash
ADMIN_TOKEN="$(openssl rand -hex 32)"
printf 'Admin token: %s\n' "$ADMIN_TOKEN"
mvn -pl backend -am spring-boot:run \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.token=$ADMIN_TOKEN"
```

From another terminal, use the printed token:

```bash
curl -H "X-Moon-Admin-Token: <printed-admin-token>" http://localhost:8080/admin/status
```

For local development only, the app can generate and log a process-local token
at startup:

```bash
mvn -pl backend -am spring-boot:run \
  -Dspring-boot.run.arguments="--moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.generate-token=true"
```

Copy the generated token from the startup log and pass it in the
`X-Moon-Admin-Token` header. This mode is opt-in so hosted runs still fail
closed unless an operator deliberately configures an admin boundary. If both
`moon.admin.token` and `moon.admin.generate-token=true` are set, the configured
token wins and no generated token is logged.

It returns process-local aggregate JSON:

- `app.status` and `app.revision`
- `providers.operations.<id>`: provider name, operation name, hourly/daily/
  monthly usage windows, configured limits, percent used when a limit is known,
  and warning state
- `providers.openMeteoGeocoding`: calls, resolved, ambiguous, not found,
  temporarily unavailable, retries, timeouts, rate limits, and latency summary
- `providers.openMeteoWeather`: calls, available, temporarily unavailable,
  retries, timeouts, rate limits, and latency summary
- `caches.geocoding` and `caches.weather`: request count, hits, misses, hit
  rate, and estimated size when the runtime Open-Meteo cache beans are active

Known-limit warning states are `ok` below 50 percent, `watch` at 50 percent,
`warning` at 80 percent, `critical` at 95 percent, and `exhausted` at 100
percent or higher. Unknown limits are explicit: `knownLimit=false`,
`limit=null`, `percentUsed=null`, and `warningState=unknown_limit`.

During a small spike, inspect `/admin/status` before and after repeated searches
for the same city. Cache hit rates should rise for repeated identical lookups,
provider quota usage should not rise on cache hits, and timeout/rate-limit
counters should stay low. Quota usage is counted at the outbound provider
transport level, so retries count as additional provider calls. These counters
use calendar-aligned UTC hourly, daily, and monthly windows.

The quota counters are deliberately process-local MVP state. They reset on
process restart and are not shared across backend instances. That is acceptable
for a single-process private alpha because it makes quota risk visible without
storing raw queries or user identifiers, but it understates usage after restarts
and across multiple instances. Before multi-instance hosting or larger public
traffic, move provider quota counters to a durable/shared store or accept the
operator risk explicitly.

When adding a new provider or provider-backed operation, use
`docs/ai-agent/checklists/provider-observability.md` before merging the
integration. A later LLM-backed fictional-location fallback should be tracked as
its own provider operation, with a kill switch and configured cost/quota limits,
not folded into real geocoding.

The status endpoint currently exposes only aggregate operational data, but it
is still intended for operator use. Do not put the admin token in a query
string or browser URL. If a reverse proxy, public tunnel, or hosting provider
also exposes `/admin/**`, keep an operator access rule there too; the backend
header token is the minimum app-level boundary.

## Alpha Hosting Notes

The current backend is suitable for a single-process private alpha. Keep one
backend replica until provider counters and caches move to a durable/shared
store. Multiple replicas would make `/admin/status` quota usage incomplete and
would reduce cache effectiveness. Public request rate limiting is not currently
backend-owned; use edge or ingress limits before public exposure, then add
application-level `429` JSON when the product API contract requires it.

Spring Boot shutdown is explicitly graceful with a 30-second per-phase
timeout. Container orchestration must send `SIGTERM` and allow more than 30
seconds before forcing termination so an in-flight provider-backed lookup can
finish.

For the current Raspberry Pi self-hosting plan, treat SD-card-backed nodes as
rebuildable. Do not add a local Postgres deployment, durable shared cache, or
long-retention logs only to support alpha hosting. Public RSS/Atom and `.ics`
outputs should start deterministic and stateless; add Postgres later when
private feeds, saved locations, alert subscriptions, durable provider counters,
or durable cache state require it.

The current planning boundary is documented in
`docs/self-hosting-alpha-plan.md`.

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
not present in the final image. The runtime uses fixed UID/GID `10001`, embeds
the source revision as `MOON_BUILD_REVISION` and OCI revision/source labels,
and checks `/readyz` from the built-in Docker health check. Pass a source
revision when building locally with:

```bash
docker build \
  --build-arg MOON_BUILD_REVISION="$(git rev-parse HEAD)" \
  -t moon-service-backend:local \
  -f backend/Dockerfile .
```

Successful `main` builds publish tested AMD64/ARM64 manifests to GHCR. The
tag, digest, permission, visibility, verification, and recovery contracts are
documented in [`docs/container-image-publication.md`](../docs/container-image-publication.md).

Run the opt-in containerized live smoke check with:

```bash
live-tests/run_container_smoke_tests.sh
```

The script builds the image with a known revision, starts the container on a
random localhost port with Open-Meteo geocoding and weather enabled, waits for
Docker readiness, verifies the revision and runtime UID, calls
`GET /api/opportunities?q=Zakopane`, verifies required API fields, stops the
container with a 35-second grace period, and writes an HTML report under
`live-tests/reports/`.

This smoke check is intentionally outside Maven because it requires Docker,
network access, and live providers.

## Run Locally

```bash
mvn -pl backend -am spring-boot:run \
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
