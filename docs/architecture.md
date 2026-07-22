# Architecture

## Current Product Shape

The current plan starts Moon Service as a website backed by a small server. The
first useful product lets a user enter a city or location and see the next
promising Moon opportunity. Keep the website first-class. Build a focused
iOS/Android companion with Expo only after the web, feed, and calendar flow is
complete and testers show recurring demand.

```text
Web MVP
  - city/location entry
  - next opportunity display
  - shareable result page
  - RSS/Atom feeds
  - `.ics` export

Small backend
  - geocoding
  - weather provider integration
  - Moon opportunity candidate generation
  - scoring rules
  - weather cache
  - provider quota counters and private admin status page
  - later recurring event pattern matching for approximate public or user-defined events
  - later terrain horizon support for exact shooting positions
  - later saved alerts and email delivery

Installed client later
  - saved locations
  - preferences
  - opportunity list UI
  - local notifications
  - bounded offline Moon calculations
```

## Future Client Boundary

The Expo companion and website may share contracts, validation, formatting,
domain logic, design rules, assets, and suitable simple components. Each
platform keeps its own complex views, web semantics and URLs, storage,
notifications, permissions, and distribution.

The installed client keeps saved places on the device and uses local
notifications first. It may cache results and perform bounded offline Moon
calculations. The backend remains authoritative when scoring uses weather, so
an offline calculation cannot replace an opportunity that the backend scored.

The backend should not require an account for the first lookup endpoint.

## Option 1: Web-First Backend MVP

A small website and backend let users enter a city or location and see upcoming
Moon opportunities without installing an app.

Benefits:

- Users can try it without installing anything.
- Photographers can share it easily.
- Testers can assess scoring quality before mobile work starts.
- The backend is already needed for weather, geocoding, caching, and scoring.
- RSS/Atom and `.ics` follow-up do not require accounts or subscriptions that
  store personal data.

Costs:

- Web notifications are less reliable than installed-client local notifications.
- Requires hosting from the beginning.
- Email or recurring personal alerts would require decisions about identity,
  consent, unsubscribe, deletion, and data retention.

## Option 2: Backendless Installed Client

An installed client calculates ephemeris, fetches weather, scores
opportunities, stores locations on the device, and schedules local
notifications.

Benefits:

- It needs little infrastructure.
- Privacy is simpler because location data can stay on the device.
- No account database.
- No hosting or backend operations.

Costs:

- Weather API keys cannot be protected if a keyed provider is required.
- Each scoring or provider fix requires an app release.
- iOS and Android background work may be delayed by platform restrictions.
- Sync, push, email, calendar, and web access are harder later.

## Option 3: Installed Client Plus Small Backend

The app remains local-first but sends locations and preferences to a backend
scoring API. The backend returns ranked Moon opportunities.

Possible first endpoint:

```text
POST /forecast-opportunities
  input: locations + preferences
  output: ranked Moon opportunities
```

Benefits:

- Weather credentials stay server-side.
- Scoring fixes and weather provider migrations can happen without app releases.
- The backend can later add push, email, calendar, remote configuration, and
  feature flags.
- Installed clients need less provider and scoring code.

Costs:

- The operator must host and monitor the service, run backups, and maintain it.
- Privacy and logging rules matter immediately.
- The client and backend add more moving parts before the app has proven value.

## Recommended MVP Direction

Use a web-first backend MVP. The research in
`docs/weather-provider-research.md` shows that a small backend is useful now.
It can protect paid API keys, cache forecast data, round coordinates before
provider calls, and apply scoring or provider changes without a client
release. The website gives testers the fastest way to decide whether the score
is useful.

Recommended boundary:

- The web UI accepts a city or location and displays ranked opportunities.
- The backend geocodes raw Unicode location queries.
- Moon Service uses browser locale only as a display and ranking hint.
- The backend fetches and caches weather by rounded coordinate and time bucket.
- The backend computes ranked opportunities from request payloads.
- The backend rate-limits public lookup requests and protects upstream provider
  quotas.
- The backend records provider call counters and cache hit and miss counts so
  operators can see provider usage.
- The backend avoids permanent location storage in the first version.
- RSS/Atom feeds and `.ics` export are the first low-friction follow-up options.
- Add recurring event matching, such as approximate flight or transport
  patterns, only after the city lookup and Moon-and-weather score are useful.
- Add email alerts later because they require storing email addresses and
  location preferences.
- Add installed-client local notifications later for recurring personal
  alerts.

### Calibration feedback storage

Issue [#33](https://github.com/rapucha/moon-service/issues/33) allows Moon
Service to collect optional calibration reports from alpha testers.
[Product notes](product-notes.md#calibration-feedback-boundary) define why the
service collects them, what each report may contain, and the privacy rules.

The service stores reports in an optional PostgreSQL store. This store is off
by default and stays separate from lookup caches and provider counters. One
positive startup setting sets the maximum number of reports and defaults to
2,000. There is no unlimited setting. The store is `near` when the report count
reaches 90% of capacity, rounded up to the next whole report, but remains below
capacity. It is `full` when the count is at least the capacity. `full` takes
precedence over `near`.

The operator warning includes only the state and the used, total, and remaining
counts. The persistence code writes this structured warning when enabled
storage starts in `near` or `full`. It also writes the warning when a report
write changes the state to `near` or `full`. The warning contains no report
text or tester data.

PostgreSQL publishes no port to the host or internet. Moon Service connects to
it through a dedicated Docker network. Missing configuration, a database or
NFS outage, or a full store may stop feedback collection. These failures must
not prevent application startup, opportunity lookup, liveness, or readiness.
The project accepts that such a failure may lose this alpha evidence. Any
future important or personal stored data needs its own backup and recovery
decision.

Agree on the product and API contracts first. Then add storage, endpoints,
browser flows, and deployment through the GitHub issues in
`docs/mvp-roadmap.md`. Enable collection only after the pull requests for those
issues merge and the host check passes. Change scoring or suggested times later
only when the collected evidence supports the change.

### Backend package ownership and dependency directions

This map shows the intended dependency direction. It does not claim that every
current package already follows the boundary. An arrow means that the source
may depend on the target.

```text
Spring configuration (composition root) -> runtime parts needed for assembly

inbound HTTP adapters -> application workflows -> backend-owned ports/models
outbound adapters -----------------------------> backend-owned ports/models

backend -> jvm-scoring-prototype (temporary)
```

The stable rules are:

- `backend.web` is an outer adapter for general HTTP and Servlet work such as
  filters, routes, headers, status codes, and error mapping. It may call
  application workflows. Application, domain, provider, and persistence code
  must not depend on HTTP filters or controllers.
- A capability shared by HTTP and non-HTTP workflows belongs outside the web
  adapter. `backend.admission.HostedAlphaProviderAdmission` owns shared
  provider admission. The opportunity filter and feedback service map its
  decisions to their own public outcomes.
- Provider adapters such as `backend.location.openmeteo` and
  `backend.weather.openmeteo` depend on backend-owned location and weather
  ports. The port packages do not depend back on provider packages.
- Spring configuration is the composition root. Configuration types may depend
  on interfaces and concrete implementations to assemble the runtime
  dependencies. They wire the parts together but do not own application or
  provider behavior.
- The Maven module direction is `backend` to `jvm-scoring-prototype`, never the
  reverse. This is a temporary migration boundary, not the desired final module
  design.

Some current source structure is transitional:

- Location and weather cache implementations depend on observability-owned
  cache-metric types, while observability decorators depend back on location
  and weather types.
- `backend.opportunity` depends on DTOs and ports in
  `backend.opportunity.search`, while that package still imports an exception
  from `backend.opportunity`.
- Packages inside the scoring prototype depend on each other in cycles, and the
  prototype exposes a broad public API.
- Backend scoring, feedback, and weather code imports several prototype types
  beyond the public `PreviewEvaluator` facade identified in the MVP roadmap.

These items record technical debt. They do not approve more dependencies in
the same directions. Keep this map conceptual and update it by hand. Do not
add an import snapshot or enforcement tool now. Reconsider one narrow automated
enforcement rule only after one of these events:

- An independent review finds a repeated violation of an approved direction.
- The scoring prototype boundary is retired or materially restructured.
- Another issue explicitly approves both a stable rule and the cost of
  enforcing it.

### Frontend source boundary

Browser source and backend source have separate boundaries. Authored and mixed
browser files live under `frontend/src/`. SVG assets that the server sends
directly live under `frontend/assets/`. Deterministic generated browser modules
live under `frontend/generated/`. The backend Maven build flattens these
directories into classpath `/static`, so Spring Boot keeps serving the same
public URLs. Root Node tooling and `tests/ui/` remain shared repository tools,
not a separate installed frontend package.

Tracked issues now cover the first missing pieces of this boundary:
coordinate-backed opportunities
([#13](https://github.com/rapucha/moon-service/issues/13)),
weather-backed scoring
([#14](https://github.com/rapucha/moon-service/issues/14)),
the web lookup/share flow
([#15](https://github.com/rapucha/moon-service/issues/15)),
feeds and calendar exports
([#16](https://github.com/rapucha/moon-service/issues/16)),
provider scalability
([#8](https://github.com/rapucha/moon-service/issues/8)), and basic
observability ([#9](https://github.com/rapucha/moon-service/issues/9)).

## Hosting Direction

Moon Service may run from a home host during alpha or beta, but that arrangement
should be temporary. `docs/self-hosting-alpha-plan.md` defines the current
self-hosting rules.

Preferred exposure:

- Cloudflare DNS and TLS.
- Cloudflare Tunnel instead of raw port forwarding.
- Do not expose unrelated home services.

Feasible early hosts:

- A Lenovo Linux laptop for a short alpha if sleep is disabled and it uses
  Ethernet.
- The existing Intel NUC for alpha or beta if the operator monitors resource
  use.
- Raspberry Pi 4/5 as a constrained alpha target. The current plan assumes
  32 GB WD Purple SD cards for storage on each node plus roughly 64-80 GB of NFS
  storage from another server. Avoid embedded etcd HA. Treat the nodes as
  rebuildable.
- Later, prefer an x86 mini PC or another SSD-backed host for easier
  Java/Spring operation and any durable database.

## Admin/Ops Surface

Before public alpha, the backend should provide a small private web page for
admin status. This page is not a full admin product or dashboard. It gives an
operator the quota and health information needed to run the service.

Recommended first route:

```text
GET /admin/status
```

Protect this route with authentication that suits the deployment environment.
The tester alpha may expose only exact `GET`/`HEAD /admin/status` behind #119's
validated 64-hex token. Production should restore a private edge such as
Cloudflare Access.

Minimum fields:

- App version/build.
- Active feature flags, including LLM fictional fallback.
- Provider call counters by provider and endpoint.
- Hourly, daily, and monthly usage estimates when the provider plan has known
  limits.
- Warning thresholds, such as 50 percent, 80 percent, and 95 percent of known limits.
- Cache hit/miss counts for geocoding, weather, and scoring.
- Recent provider errors and timeouts.
- Current public API rate-limit settings.

Moon Service may need local counters because providers may not report quota use
in real time. Count outbound calls from the backend and compare them with the
configured provider limits.

Possible automatic responses:

- Warn admin at 50 percent usage.
- Treat 80 percent usage as operational risk.
- Disable nonessential work near 95 percent, such as LLM Easter eggs or
  aggressive feed refresh.
- Keep core lookup available as long as cached data is useful.

## Expected Future Stack

Backend:

- Java 21.
- Spring Boot REST API.
- Postgres.
- Flyway or Liquibase.
- Scheduled jobs once feed generation or email exists.
- Weather provider client.
- Ephemeris and scoring engine.

Installed client, after the web, feed, and calendar flow is complete and
testers show recurring demand:

- Use Expo for a focused iOS/Android companion while keeping the website.
- Keep canonical geocoding, weather, ephemeris, and scoring in the backend by
  default.
- Treat local notifications, background work, secure storage, permissions,
  backup, and distribution as explicit platform seams.
- Allow bounded offline Moon calculations, but keep weather-backed scoring
  authoritative in the backend.
- Retain the current responsive web UI unless a separately reviewed migration
  demonstrates enough value.

## Key Unresolved Architecture Choices

- First MVP boundary: web-first backend lookup is now favored. The first API
  shape is documented in `docs/api-shape.md`.
- Geocoding provider: Open-Meteo Geocoding is the first candidate for city and
  town lookup. Exact-address autocomplete remains out of scope.
- Internationalized search: raw Unicode input must work even when the browser
  locale is generic or English.
- Ephemeris implementation: Astronomy Engine `2.1.19` is accepted for the JVM
  backend under `docs/ephemeris-research.md`; a future client may perform
  bounded offline Moon calculations, but uses backend weather-backed scoring.
- Weather provider: Open-Meteo is the first candidate. The project still needs
  to test forecast quality and confirm whether alpha use is strictly
  non-commercial.
- Weather cache: decide whether to use Postgres immediately or start with a
  simpler cache during the first scoring prototype.
- Admin and operations storage: decide where to keep provider call counters,
  cache metrics, and recent errors before a full database exists.
- Identity timing: one-off lookup has no identity. Add optional email or an
  anonymous identity only when saved alerts require it.
- Client delivery: Expo is a later iOS/Android companion. It does not replace
  the current web UI.
- Notification timing: RSS/Atom and `.ics` first; email and installed-client
  local notifications later.
- Recurring event context: decide whether to start with approximate patterns
  entered by users, curated public patterns, or schedules from a provider.
  Also decide how to represent uncertain timing, cancellations, and route
  changes.
- Distribution/community: Reddit is manual/community validation only; Mastodon and Bluesky are not planned.
- Data retention: decide whether backend request logs may contain coordinates
  and how to remove or reduce them.
- Terrain horizon modeling: wait until users can provide exact shooting
  positions. A city-level lookup cannot reliably account for hills, buildings,
  or trees.
- Hosting target for alpha: dev laptop, NUC, Raspberry Pi, x86 mini PC, or
  hosted VPS. Tracked by
  [#19](https://github.com/rapucha/moon-service/issues/19).
