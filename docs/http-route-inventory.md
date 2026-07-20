# HTTP Route Inventory

This is the canonical inventory of HTTP operations explicitly mapped by Moon
Service controllers. It records who uses each route, why it exists, and how its
exposure differs between the ordinary application and hosted-alpha mode.

The route universe is the ten mappings declared by `WebPageController`,
`OpportunitySearchController`, `CalibrationFeedbackController`,
`HealthController`, and `AdminStatusController`. Spring's implicit `HEAD`
handling, `/error`, exception handlers, and static-resource serving are
behavior around those mappings, not additional inventory entries. Outbound
Open-Meteo URLs are provider dependencies, not Moon Service routes.

## Summary

| Operation | Role and lifecycle | Current production consumer | Hosted alpha |
| --- | --- | --- | --- |
| `GET /` | Web entry page; current product route | Web browser | Allowlisted; whole-site bound |
| `GET /search` | Lookup and share page; current product route | Web browser | Allowlisted; whole-site bound |
| `GET /about` | Product/privacy information page | Web browser | Allowlisted; whole-site bound |
| `GET /api/opportunities` | Location-to-opportunity product API | Browser `app.js` | Allowlisted; site and search bounds |
| `POST /api/opportunities/search` | Direct fixture/scoring prototype contract | None | Hidden after site admission |
| `GET /api/calibration-feedback/v1/capability` | Public feedback feature/availability state | None yet | Allowlisted; exempt from hosted resource admission |
| `POST /api/calibration-feedback/v1/submissions` | Bounded current-observation feedback write | None yet | Allowlisted POST; provider-bound resolution and feedback write bucket |
| `GET /healthz` | Process liveness probe | None | Hidden after site admission |
| `GET /readyz` | Deployment readiness probe | Docker and Pi deployment tooling | Allowlisted; narrow Docker bypass |
| `GET /admin/status` | Operator diagnostics | Human operator | Site-bound and token protected |

## Shared behavior and exposure

- `@GetMapping` operations also receive Spring MVC's implicit `HEAD` handling;
  `HEAD` is not a separate application-authored mapping.
- Requests that reach `RequestLoggingFilter` receive `X-Request-Id`. Logging
  records the path but not the query string, so location values are not written
  by that filter.
- With `moon.hosted-alpha.enabled=false` (the default), mappings are available
  wherever the configured listener is reachable. Deployment binding determines
  whether that means loopback or an explicitly trusted LAN address.
- In hosted-alpha mode, resource admission runs before surface policy and admin
  authentication. Except for the exact Docker readiness probe and the two exact
  calibration-feedback paths described below, every request that reaches this
  filter first consumes from one configured process-wide bucket. Its default
  and maximum allowed hosted capacity is 40, with a default and fastest allowed
  refill of one token per second; stricter settings are valid. An empty bucket
  returns `429` with a numeric `Retry-After` before the route's usual `200`,
  `400`, `401`, `404`, `405`, or `503` behavior can be selected. `GET` returns
  canonical `rate_limited` JSON; `HEAD` carries the same status, headers, and
  would-be content length without a body.
- Exact `GET`/`HEAD /api/opportunities` requests that pass the whole-site bound
  must also acquire a concurrent-search permit and consume from a provider
  bucket. The defaults and maximum allowed hosted settings are two concurrent
  provider operations, ten provider tokens, and a one-token-per-minute refill;
  stricter settings are valid. Either bound can return `429`; the concurrency permit is
  released when downstream handling finishes. The same two resources wrap
  feedback location resolution as described below; they do not apply to pages,
  static files, admin status, readiness, or the fixture POST route.
- Whole-site bypasses are the bodyless `GET /readyz` whose connector reports a
  loopback remote address and `Host: localhost`, matching the Docker health
  check, and both exact feedback paths. Other readiness requests still consume
  whole-site capacity. Capability performs no provider work. After early
  disabled, replay, conflict, and storage decisions, feedback location
  resolution uses the shared provider token and concurrency guard. Successful
  resolution can then reach the separate process-wide 12-token feedback write
  bucket, which restores one whole token per complete hour. Resolver failure or
  provider-admission refusal consumes no feedback write token.
- Hosted-alpha mode exposes only exact allowlisted paths. It allows bodyless
  `GET` or `HEAD` on every approved path except feedback submissions, where it
  allows only `POST` and passes the body to the route's 16,384-byte bound. It
  adds the hosted security headers, returns empty `404` for hidden or unknown
  paths, empty `405` with a path-specific `Allow` value for disallowed methods,
  and empty `400` for a framed `GET` or `HEAD` body. The feedback routes send no
  permissive CORS headers, and `OPTIONS` does not provide preflight support.
  Tomcat rejects `TRACE` before the application filter, so those application
  headers and empty-body guarantees do not apply to `TRACE`.
- The web lookup is anonymous and creates no durable user profile or preference.
  A `q` value crosses the Open-Meteo geocoding boundary; normalized queries or
  location IDs and their results are held in a bounded, process-local cache.
  Resolved location data then drives an Open-Meteo weather lookup.

Implementation authority: [request logging](../backend/src/main/java/dev/moonservice/backend/observability/RequestLoggingFilter.java),
[hosted resource-limit filter](../backend/src/main/java/dev/moonservice/backend/web/HostedAlphaResourceLimitFilter.java),
[hosted-alpha surface filter](../backend/src/main/java/dev/moonservice/backend/web/HostedAlphaSurfaceFilter.java),
and [hosted-alpha functional tests](../backend/src/test/java/dev/moonservice/backend/web/HostedAlphaSurfaceFunctionalTest.java).

## Browser pages

### `GET /`

- **Handler:** `WebPageController.searchPage` internally forwards to
  `/index.html`; it is not a redirect and sends no `Location` header.
- **Purpose/audience:** zero-install browser entry point for anonymous users.
- **Production invocation:** direct navigation to the service root.
- **Other callers:** application functional tests.
- **Authentication/data:** none; the route itself has no input.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [functional test](../backend/src/test/java/dev/moonservice/backend/OpportunitySearchFunctionalTest.java).

### `GET /search`

- **Handler:** `WebPageController.searchPage`, with the same internal forward to
  `/index.html`.
- **Purpose/audience:** current lookup page and shareable result URL.
- **Production invocation:** navigation links, browser history, and generated
  share links use `/search?q=...` or `/search?locationId=...`. Browser code reads
  those parameters and calls `GET /api/opportunities`; `locationId` wins if both
  are present in a page URL.
- **Other callers:** browser and application functional tests.
- **Authentication/data:** none. The URL can contain a location query or
  selected location ID and is therefore visible in browser history/share links.
  The browser also keeps up to five successful display names, location IDs, and
  timezones in its own `localStorage`; this storage is optional and client-side.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [browser flow](../frontend/src/app.js),
  [browser storage](../frontend/src/recentSearches.js),
  [share paths](../frontend/src/api.js).

### `GET /about`

- **Handler:** `WebPageController.aboutPage` internally forwards to
  `/about.html`.
- **Purpose/audience:** product purpose, privacy posture, and caveats for users.
- **Production invocation:** navigation from the search page and direct visits.
- **Other callers:** application functional tests.
- **Authentication/data:** none; the route has no input.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [page](../frontend/src/about.html).

## Product and prototype APIs

### `GET /api/opportunities`

- **Handler:** `OpportunitySearchController.searchByQuery`.
- **Purpose/lifecycle:** current anonymous product lookup API.
- **Why it exists:** this route turns browser-level location intent into the
  complete server-owned opportunity workflow. A free-text `q` starts geocoding
  and can return candidate choices; `locationId` continues from a selected
  candidate without another fuzzy search. After resolution, the server applies
  its current search defaults, fetches live weather, generates Moon windows,
  and scores them. The `/search` page can therefore reconstruct an idempotent
  lookup from its shareable URL without knowing prototype scoring controls,
  provider IDs, coordinates, or weather contracts.
- **Production invocation:** browser `app.js` calls it through `api.js`; query
  searches use `q`, while an ambiguity selection uses `locationId`. The browser
  does not call the direct POST route below.
- **Other callers:** manual HTTP/Postman requests, UI tests, application tests,
  and container/live smoke checks.
- **Request:** exactly one usable `q` or `locationId`; values are trimmed,
  limited to 100 Unicode code points, and reject control/bidirectional-format
  characters. Query whitespace is collapsed.
- **Response:** `200 application/json` for `ok`, `ambiguous_location`, or
  `location_not_found`; `400` with `invalid_request` for invalid input; `503`
  with `temporarily_unavailable` for unavailable location or weather lookup.
  The full `ok` shape includes location, evaluated windows, opportunities,
  rejected windows, and messages. Hosted-alpha resource admission can instead
  return `429` with `rate_limited`, `retryAfterSeconds`, and `Retry-After`
  before the controller runs.
- **Authentication/data:** anonymous. `q` is sent to Open-Meteo geocoding;
  normalized queries or location IDs and resolution results are cached in the
  current process with bounded size and status-specific TTLs. Resolved location
  data drives the Open-Meteo weather request. Responses include coordinates,
  timezone, weather, and Moon data, but no durable user profile is created.
- **Exposure:** available on the ordinary listener. Hosted alpha allowlists
  bodyless `GET`/`HEAD`, but applies the configured whole-site, search
  concurrency, and provider-token bounds before controller/provider work.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
  [service validation](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchService.java),
  [server defaults](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchDefaults.java),
  [geocoding cache](../backend/src/main/java/dev/moonservice/backend/location/CachingLocationResolver.java),
  [response model](../backend/src/main/java/dev/moonservice/backend/opportunity/search/OpportunitySearchResponse.java),
  [API design](api-shape.md).

### `POST /api/opportunities/search`

- **Handler:** `OpportunitySearchController.search`.
- **Purpose/lifecycle:** older deterministic direct scoring/prototype contract;
  it preserves prototype/parity testing and is not the browser API. It bypasses
  runtime location resolution and the live weather-provider path, so its
  long-term public lifecycle is undecided.
- **Production invocation:** none.
- **Other callers:** scoring/application tests and manual HTTP, curl, or Postman
  debugging tools.
- **Request:** JSON object with required `locationId`, `start`,
  `forecastHorizonDays`, `maxMoonAltitudeDegrees`, and `limit`. Only
  `prague-cz` is supported because the direct path delegates to the scoring
  prototype's one-entry fixture registry; it does not call `LocationResolver`.
  `start` accepts an ISO date or UTC instant; ranges are 1–30 days, 0–90
  degrees, and 1–100 results.
- **Response:** `200 application/json` with the opportunity result; malformed,
  incomplete, unsupported, or out-of-range input returns `400` with
  `invalid_request`, `generatedAt`, and a message.
- **Authentication/data:** unauthenticated in ordinary mode; inputs are bounded
  to the saved Prague fixture and are not stored as user data.
- **Exposure:** reachable on the ordinary listener. In hosted alpha it consumes
  whole-site capacity first and is then hidden as `404`; exhaustion can
  therefore return `429` before the surface filter returns `404`. It never uses
  the search concurrency permits or provider bucket.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
  [request model](../backend/src/main/java/dev/moonservice/backend/opportunity/search/OpportunitySearchRequest.java),
  [direct engine path](../backend/src/main/java/dev/moonservice/backend/opportunity/scoring/ScoringOpportunitySearchEngine.java),
  [fixture registry](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/fixture/Locations.java),
  [manual requests](../backend/http/README.md).

### `GET /api/calibration-feedback/v1/capability`

- **Handler:** `CalibrationFeedbackController.capability`.
- **Purpose/lifecycle:** disabled-by-default public state for the bounded alpha
  feedback feature. A client can decide whether to offer a new submission
  without learning storage or provider details.
- **Production invocation:** none yet; browser feedback controls are a separate
  slice.
- **Other callers:** functional tests and manual HTTP clients.
- **Request:** no body or authentication. The route does not reserve storage or
  consume a feedback write token.
- **Response:** always `200 application/json` and `Cache-Control: no-store`, with
  schema version, server time, `featureState`, and `submissionAvailability`.
  A disabled feature maps to `disabled/disabled`. With the feature enabled,
  disabled or incompletely configured persistence maps to `enabled/disabled`;
  unavailable or full persistence, or a known unavailable resolver/astronomy
  dependency, maps to `enabled/unavailable`; normal or near-capacity storage
  with available dependencies maps to `enabled/available`. Write-token
  exhaustion does not change this state.
- **Authentication/data:** anonymous. The response omits database type,
  settings, capacity, counts, resolver/provider details, and failure text.
- **Exposure:** available on the ordinary listener. Hosted alpha allowlists
  bodyless `GET`/`HEAD` and applies security headers. It exempts the path from
  the whole-site limiter and performs no provider or concurrency work.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackController.java),
  [service](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackService.java),
  [wire contract](openapi/calibration-feedback-v1.yaml),
  [behavior contract](api-shape.md#calibration-feedback-api).

### `POST /api/calibration-feedback/v1/submissions`

- **Handler:** `CalibrationFeedbackController.submit`.
- **Purpose/lifecycle:** accept one anonymous current-observation calibration
  report copied from a currently loaded opportunity.
- **Production invocation:** none yet; browser feedback controls are a separate
  slice.
- **Other callers:** functional tests and explicit manual alpha clients.
- **Request:** UTF-8 `application/json` only, with optional `charset=utf-8`, no
  content encoding, and at most 16,384 received bytes. The closed object needs
  schema version 1, a lowercase canonical UUIDv4 `clientSubmissionId`, the
  loaded `locationId`, the loaded `opportunityId`, and at least one non-null
  `ambientLight`, `crescentVisibility`, or normalized `notes` value. Unknown or
  duplicate members, explicit `null`, malformed Unicode, and values outside the
  contract bounds are rejected.
- **Processing/idempotency:** after the bounded body arrives, the server captures
  one microsecond-precision receipt instant, normalizes the request, and hashes
  the five fixed semantic slots with the versioned framing and SHA-256 in the
  behavior contract. It checks the feature, then performs an early repository
  lookup by client UUID. Exact replay and changed-payload conflict finish before
  location resolution or token admission. A new report checks repository
  status, resolves the canonical location ID, consumes one feedback write
  token, recomputes Moon altitude, Moon illumination, Sun altitude, and light
  bucket, and stores transactionally. The stored `applicationRevision` is
  supplied by the server and is not accepted from the request.
- **Admission:** one process-wide bucket starts with 12 tokens and restores one
  whole token per complete monotonic hour, capped at 12. A token consumed by a
  new report is not restored after later astronomy, persistence, capacity-race,
  transactional replay, or conflict outcomes. The bucket resets on process
  restart and is not shared across instances.
- **Response:** `201 created` for a new row and `200 replayed` for an exact
  retry. Stable errors cover invalid JSON/request (`400`), UUID content conflict
  (`409`), oversized body (`413`), unsupported media (`415`), location/report
  rejection (`422`), token exhaustion (`429` with matching `Retry-After` and
  JSON seconds), and generic feedback unavailability (`503`). All responses
  use `Cache-Control: no-store` and never echo report or dependency details.
- **Authentication/data:** anonymous and same-origin for browser use. The route
  requests no GPS permission and sends no permissive CORS headers or preflight
  support. Controlled logs may keep method, route, status, duration, request
  ID, coarse outcome, and aggregate capacity warnings, but not request bodies,
  identifiers, evidence, notes, UUIDs, astronomy, IP/forwarded identity, or
  User-Agent. Persistence or dependency failure disables only feedback writes;
  startup, opportunity lookup, liveness, and readiness remain independent.
- **Exposure:** available on the ordinary listener. Hosted alpha allows only
  `POST` on this exact path, permits its bounded body, and exempts it from the
  hosted whole-site limiter. At the resolver step it shares the hosted provider
  and concurrency guards; after successful resolution it uses the stricter
  feedback write bucket above. `OPTIONS` receives no preflight support.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackController.java),
  [service](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackService.java),
  [persistence](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackRepository.java),
  [wire contract](openapi/calibration-feedback-v1.yaml),
  [behavior contract](api-shape.md#calibration-feedback-api).

## Operational routes

### `GET /healthz`

- **Handler:** `HealthController.liveness`.
- **Purpose/lifecycle:** provider-independent process liveness probe.
- **Production invocation:** none in the current deployment.
- **Other callers:** application and container live tests; operators may probe
  it manually in ordinary mode.
- **Response:** `200` with `{status: "ok", revision}` while liveness is correct,
  otherwise `503` with `{status: "unavailable", revision}`; JSON and
  `Cache-Control: no-store` in both cases.
- **Authentication/data:** none; exposes only health state and build revision.
- **Exposure:** available on the ordinary listener. In hosted alpha it consumes
  whole-site capacity first and is then hidden as `404`, so exhaustion can
  return `429` before route hiding.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/HealthController.java),
  [live container test](../live-tests/test_container_backend.py).

### `GET /readyz`

- **Handler:** `HealthController.readiness`.
- **Purpose/lifecycle:** provider-independent readiness and deployment revision
  probe.
- **Production invocation:** Docker image health checking and Raspberry Pi
  deploy/control scripts.
- **Other callers:** image-publication verification, CI/live smoke checks, and
  manual deployment verification.
- **Response:** `200` with `{status: "ok", revision}` while accepting traffic,
  otherwise `503` with `{status: "unavailable", revision}`; JSON and
  `Cache-Control: no-store` in both cases.
- **Authentication/data:** none; exposes only readiness and build revision.
- **Exposure:** available on the ordinary listener and allowlisted in hosted
  alpha. The exact Docker probe bypasses resource admission; Pi, CI, public, and
  manual requests consume whole-site capacity and can receive `429`.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/HealthController.java),
  [Docker health check](../backend/docker/healthcheck.sh),
  [Pi deployment](../deployment/raspberry-pi/README.md).

### `GET /admin/status`

- **Handler:** `AdminStatusController.status`, guarded by `AdminAccessFilter`.
- **Purpose/lifecycle:** small operator-only view of build revision, provider
  outcomes/retries/quota windows, and cache counters; it is not a user account
  or general admin application.
- **Production invocation:** human operator inspection; no automated runtime
  consumer is currently established.
- **Other callers:** functional/unit tests and manual curl requests.
- **Authentication/data:** `X-Moon-Admin-Token` is required. With admin routes
  disabled, `/admin/**` returns `404`; a missing or wrong configured token gives
  `401`. The response contains process-level metrics, not raw location queries.
- **Exposure:** conditional on admin configuration in ordinary mode. Hosted
  alpha requires an explicit 64-hex token and exposes only exact
  `/admin/status`. Every attempt consumes whole-site capacity before method,
  body, or token policy, so `429` can precede `400`, `401`, or `405`; both the
  limiter and downstream policy preserve hosted security headers and
  `Cache-Control: no-store`.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/AdminStatusController.java),
  [access filter](../backend/src/main/java/dev/moonservice/backend/web/AdminAccessFilter.java),
  [operator documentation](../backend/README.md#operational-health).

## Deliberate non-routes

- Files under `frontend/src/`, `frontend/assets/`, and `frontend/generated/`
  are packaged into classpath `/static`. They support the three browser mappings
  but are not independent controller operations in this inventory.
- `/error` is Spring Boot's internal error-dispatch path, not an application
  controller mapping. `/test/slow` exists only in `GracefulShutdownTest`.
- `/l/{location}`, `/feeds/*.atom`, `/calendars/*.ics`, and `/o/*.ics` are
  design/roadmap shapes, not implemented routes. Opportunity JSON currently
  carries reserved `/o/*.ics` strings, but no controller serves them and the
  browser does not expose a calendar action without an `icsReady` signal.
- There are no Actuator, OpenAPI, Swagger UI, or Spring REST Docs endpoints.

## Maintenance rule

The change that adds, removes, or changes a controller operation must update
this inventory. The same applies when a production client starts or stops using
an operation, or when authentication, exposure, sensitivity, or lifecycle
changes. Keep production consumers distinct from tests, manual tools, CI,
deployment probes, and prototypes. Controller code and functional tests remain
the implementation evidence; [API shape](api-shape.md) remains the product
design authority for future contracts.
