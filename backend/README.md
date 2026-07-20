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
- Disabled-by-default calibration feedback capability at
  `GET /api/calibration-feedback/v1/capability` and bounded report submission at
  `POST /api/calibration-feedback/v1/submissions`.
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
- Opportunity Moon summaries include `phaseAngleDegrees`; every Moon path/pass
  point includes the corresponding `moonPhaseAngleDegrees` for its own sample
  time. Both also include optional observer-oriented `brightLimbTiltDegrees`
  and `northPoleTiltDegrees`. Clients retain the location-independent phase-mask
  and north-up texture fallbacks independently when either tilt is unavailable;
  lunar libration remains out of scope.
- Open-Meteo weather forecast adapter code under `backend.weather.openmeteo`,
  covered by saved provider JSON fixtures. It can be selected for live hourly
  forecast lookup with `moon.weather.provider=open-meteo`.
- HTTP `400` error mapping for malformed JSON and invalid opportunity search
  requests.

Durable persistence is limited to the disabled-by-default calibration-feedback
repository described below. Durable/shared caches, accounts, cookies, feeds,
calendar generation, and deployment configuration remain out of scope.
Missing or unknown `moon.location.resolver` or `moon.weather.provider` values
fail startup; the runtime backend does not include fixture provider modes.

For the canonical list of implemented controller routes, their consumers, and
default versus hosted-alpha exposure, see the
[HTTP route inventory](../docs/http-route-inventory.md).

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
shared deployment lifecycle defaults and prevents Spring's generic JDBC and
Flyway auto-configuration from creating an implicit database dependency.
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
| `moon.hosted-alpha.enabled` | `false` | Enables the fail-closed Spring application surface for temporary Funnel hosting. Requires an explicit 64-hex-character admin token and applies to LAN and tunneled requests alike. |
| `moon.resource-limits.whole-site-capacity` | `40` | Maximum initial or accumulated hosted-alpha request burst shared by the process. |
| `moon.resource-limits.whole-site-refill-interval` | `1s` | Interval that restores one shared whole-site token. |
| `moon.resource-limits.provider-lookup-capacity` | `10` | Maximum initial or accumulated burst of accepted provider-backed lookups. |
| `moon.resource-limits.provider-lookup-refill-interval` | `1m` | Interval that restores one provider-backed lookup token. |
| `moon.resource-limits.opportunity-concurrency` | `2` | Maximum concurrent provider-backed opportunity searches or feedback location resolutions in hosted mode. |
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
| `moon.feedback.enabled` | `false` | Enables the public calibration-feedback feature. This is separate from enabling and configuring its private store. |
| `moon.feedback.persistence.enabled` | `false` | Opts into the private calibration-feedback store only when all connection settings are also present. |
| `moon.feedback.persistence.jdbc-url` | unset | Private PostgreSQL JDBC URL. Other database schemes keep persistence unavailable. |
| `moon.feedback.persistence.username` | unset | Private PostgreSQL role used only by feedback persistence. |
| `moon.feedback.persistence.password` | unset | Host-supplied feedback database secret. Never commit it or put it in command history. |
| `moon.feedback.persistence.capacity` | `2000` | Positive report limit up to 2,000. Invalid values keep persistence unavailable. |

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

### Calibration feedback

The backend implements these two same-origin alpha routes:

```http
GET /api/calibration-feedback/v1/capability
POST /api/calibration-feedback/v1/submissions
```

The public feature and its private store are disabled independently. The
service reads the feature flag with the Spring placeholder
`${moon.feedback.enabled:false}`; set `moon.feedback.enabled=true` to enable
submission behavior. Separately set
`moon.feedback.persistence.enabled=true` and provide a PostgreSQL JDBC URL,
username, and nonempty password to enable storage. Missing connection settings
leave the repository disabled. An invalid capacity, unsupported JDBC scheme,
migration failure, or database outage makes only feedback persistence
unavailable.

The capability route always returns `200` and `Cache-Control: no-store`. It
publishes only `featureState` and `submissionAvailability`, alongside schema
version and server time. The public states map as follows:

| Feature and runtime state | Feature state | Submission availability |
| --- | --- | --- |
| Feature disabled, regardless of storage | `disabled` | `disabled` |
| Feature enabled, persistence disabled or settings incomplete | `enabled` | `disabled` |
| Feature enabled, persistence startup/current status unavailable or storage full | `enabled` | `unavailable` |
| Feature enabled, a resolver or astronomy dependency known unavailable | `enabled` | `unavailable` |
| Feature enabled, storage normal or near capacity and dependencies available | `enabled` | `available` |

The response does not reveal the database type, settings, capacity, counts,
provider details, or failure text. It is a current status, not a reservation;
temporary write-token exhaustion does not change it. Resolver and astronomy
failures discovered only while handling a report still return generic
unavailability for that submission.

Submission accepts only UTF-8 `application/json`, optionally with
`charset=utf-8`, and no content encoding. The received body limit is 16,384
bytes. The request is a closed object: unknown or duplicate members, explicit
`null`, invalid Unicode, malformed JSON, invalid identifiers, and requests with
no usable evidence are rejected. The exact fields, normalization, bounds, and
error mappings live in [the API contract](../docs/api-shape.md#calibration-feedback-api).

After receiving the bounded body, the server captures one microsecond-precision
receipt instant and normalizes the report. It hashes the five fixed semantic
slots with the versioned framing and SHA-256 defined by the API contract. A
disabled feature returns before repository access. Otherwise an early lookup
by `clientSubmissionId` returns an exact replay or changed-payload conflict
before location resolution or rate limiting. A new report checks storage,
resolves the canonical location ID, consumes one write token, recomputes the
four current astronomy facts, and then stores the report transactionally.

The process-wide feedback write bucket starts with 12 tokens and restores one
whole token per complete hour using a monotonic clock. Exact early replays and
conflicts do not consume it. Once a new report reaches admission, a later
astronomy, persistence, capacity-race, replay, or conflict result does not
restore the token. Exhaustion returns `429` with matching numeric
`Retry-After` and `error.retryAfterSeconds` values. The bucket resets on process
restart and is not shared between instances.

All feedback responses use `Cache-Control: no-store`. The submission route
sends no permissive CORS headers and provides no cross-origin preflight. Logs
may contain method, route, status, duration, request ID, coarse outcome, and
aggregate capacity warnings. They do not retain report bodies, location or
opportunity IDs, evidence, notes, feedback UUIDs, astronomy values, IP
addresses, forwarded identity, or User-Agent. Feedback database failures map
to generic feedback unavailability without preventing startup, opportunity
lookup, liveness, or readiness.

#### Database provisioning and migrations

The deployment must provide a running PostgreSQL server, an existing database,
and a role with the credentials and permissions needed to create and use the
feedback tables. Spring, Hikari, and Flyway do not create the PostgreSQL server,
database, role, or credentials.

`FeedbackPersistenceConfiguration` is an application configuration class that
Spring discovers during startup. With complete settings, it creates a private
Hikari connection pool, configures Flyway with that pool, and calls
`Flyway.migrate()` before opening the bounded repository. The checked-in
exclusion of Spring Boot's generic `DataSource` and Flyway auto-configuration
remains in effect. It stops Spring Boot from creating a database dependency for
the whole application; it does not disable the Flyway library or this explicit
migration call.

Flyway is the database-schema migration runner. It scans
`classpath:db/migration` for versioned scripts. On the first successful startup
against an empty configured database, it creates `flyway_schema_history`, runs
`V1__create_calibration_feedback.sql`, and records the migration version and
checksum. The V1 script creates the feedback tables, constraints, and index
inside the existing database. On later startups, Flyway validates the recorded
migration and applies only newer pending scripts. Once V1 has been deployed,
schema changes should be added as a new migration such as `V2__description.sql`
instead of editing V1.

Each feedback row stores the loaded opportunity ID, its backend location ID,
one server receipt instant, optional ambient-light and crescent-visibility
evidence, optional normalized notes, server-computed Moon altitude and
illumination, Sun altitude, the light bucket, server-retained
`applicationRevision`, `serverReportId`, `clientSubmissionId`, and the
idempotency hash. At least one
evidence field is required; omitted fields stay null instead of becoming an
`unknown` rating.

Notes may use any language, mixed scripts, and emoji. Stored notes must be NFC,
have no outer Unicode whitespace or U+0000, and contain 1–4,000 Unicode code
points. Submission handling constructs the normalized stored shape and exact
idempotency hash; the repository and schema enforce it. The schema creates no
account, visitor identity, request-body, IP-address, forwarded-identity, or
User-Agent field. Reports stay until a later operator tool deletes one by
`serverReportId`.

Capacity defaults to 2,000 and can be lowered for a deployment. Exact replay
and changed-payload conflict checks happen before capacity refusal. A database
row lock serializes create and delete operations so concurrent writers cannot
cross the configured limit. Aggregate warnings contain only state and counts
when enabled storage starts near or full, or a create moves it into either
state.

The application does not publish the feedback `DataSource` as its general
database. Failed feedback startup or later database calls return repository
outcomes instead of changing application availability. Opportunity lookup,
`/healthz`, and `/readyz` do not call this repository.

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

### Hosted-alpha application surface

`moon.hosted-alpha.enabled` is disabled by default. Set
`MOON_HOSTED_ALPHA_ENABLED=true` only for a deliberately prepared hosted-alpha
deployment. Because Docker NAT does not reliably distinguish the direct LAN
listener from Funnel's loopback target, enabling the mode applies the same
policy to every request reaching that application instance.

The enabled policy allows `GET` and `HEAD` for `/`, `/search`, `/about`, their
backing HTML files, the exact static files tracked by the current build,
`/api/opportunities`, `/readyz`, exact `/admin/status`, and the feedback
capability route. It separately allows only `POST` for the feedback submission
route so that route can receive its bounded JSON body. Adding a static file
does not publish it automatically; update the explicit allowlist and test
inventory. Every other `/admin/**` path, the fixture endpoint, `/healthz`, and
every unapproved path returns `404`, even with the admin token. An unapproved
method on an approved path returns `405` with the path-specific `Allow` value;
a framed `GET` or `HEAD` body returns `400` before authentication. The feedback
routes send no permissive CORS headers, and `OPTIONS` is not an allowed
cross-origin preflight method.

Hosted startup requires an explicit 64-hex-character `moon.admin.token`
generated with `openssl rand -hex 32`; this validates format, not randomness.
Missing, malformed, surrounding-space, or generated-token configurations fail.
Exact `/admin/status` returns `401` for a missing/wrong token, `200` for the
configured token, and `Cache-Control: no-store` for every response.

Before controller handling, the policy removes `Forwarded`, common client-IP,
Cloudflare, and Tailscale identity/capability headers. Moon Service uses none of
them for identity or authorization, and its outer request logger records none
of their values. Spring-chain responses receive CSP, HSTS, frame-denial,
no-sniff, no-referrer, permissions, and same-origin opener/resource headers.
The CSP permits the current UI's generated data images, score-bar widths, and
SVG silhouette-layer styles. Connector-level rejections such as disabled
`TRACE` and malformed requests remain outside this application boundary.

### Hosted-alpha resource bounds

Hosted-alpha mode adds one process-local admission boundary before the hosted
surface and admin authentication. Every request except the exact Docker
readiness probe and the two exact feedback paths shares the whole-site bucket.
Exact `/admin/status` attempts consume that capacity even when their method,
body, or token is rejected; an admin `429` remains `no-store` and carries the
hosted security headers.

Exact `GET`/`HEAD /api/opportunities` requests also require one provider token
and one of two concurrent provider-operation permits before controller or
provider work.
The fixture POST, static files, admin status, and readiness do not consume those
two resources. A rejection returns HTTP `429`, canonical `rate_limited` JSON,
and a numeric `Retry-After` hint. The Docker carve-out is only bodyless
`GET /readyz` from loopback with `Host: localhost`; public readiness requests
remain inside the whole-site bound.

Both feedback paths bypass the hosted whole-site bucket so capability can keep
its always-`200` contract and submission errors keep their feedback shape.
Capability is a cheap state read. After the early replay and storage decisions,
a new submission runs location resolution through the shared hosted provider
token and concurrency guard. Provider-admission refusal is generic feedback
unavailability. Successful resolution then reaches the stricter
feedback-owned, process-wide 12-token write bucket described above; replay,
conflict, disabled, unavailable, full, and location-resolution failures are
decided before that write token is consumed.

Hosted startup rejects a weaker resource setting or more than one configured
Open-Meteo retry. Ten initial tokens plus 1,440 minute refills admit 1,450 lookups in an
uninterrupted 24-hour run. Conservatively counting two geocoding and two weather
attempts per lookup gives 5,800 calls, 4,200 below Open-Meteo's current
[10,000-call threshold](https://open-meteo.com/en/terms). The current fixed
one-location, seven-day, eight-variable weather request counts as one call under
the published [pricing rules](https://open-meteo.com/en/pricing). A restart
restores both bursts, so this is not a durable calendar-day quota. No Tomcat
connector limit or network-shaping guarantee is implied.
The two-page [resource-admission diagrams](../docs/diagrams/hosted-alpha-resource-limits.pdf)
show the filter sequence and TokenBucket refill, consumption, and retry mechanics.

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
`X-Moon-Admin-Token` header. This mode is opt-in and local-development only. A
non-hosted run uses a configured token instead when both settings are present;
hosted-alpha mode rejects generation even when a configured token also exists.

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
string or browser URL. For the temporary tester alpha, exact `/admin/status` is
the only publicly routable admin path and the validated header token is its
application boundary. Production hosting should add an edge operator-access
rule such as Cloudflare Access rather than broaden this exception.

## Alpha Hosting Notes

The current backend is suitable for a single-process private alpha. Keep one
backend replica until provider counters and caches move to a durable/shared
store. Multiple replicas would make `/admin/status` quota usage incomplete and
would reduce cache effectiveness. The disabled-by-default hosted surface is
implemented under [#119](https://github.com/rapucha/moon-service/issues/119).
Before temporary Funnel exposure,
[#120](https://github.com/rapucha/moon-service/issues/120) will add process-local
shared limits through Spring-managed application components. Those controls
bound accepted work and provider use after requests reach the Pi; they are not
an inbound WAN cap. Cloudflare remains the later production edge.

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
The digest-pinned Docker Compose host and Raspberry Pi operator procedures are
documented in `deployment/raspberry-pi/README.md`.

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

### Application functional tests

The `functional` JUnit tag groups the default and hosted-alpha application HTTP
tests. Both start the real Spring application on a random port and exercise its
filters, controllers, serialization, and static resources. They use test
provider doubles or local fixtures and require no live provider, container,
Raspberry Pi, LAN, or public-network access.

Run only this suite with:

```bash
mvn -pl backend -am -Dgroups=functional test
```

The normal backend verification command above continues to include these
functional tests.

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
