# Architecture

## Current Product Shape

The leading architecture is backend-backed with a web-first discovery surface.
The first useful product lets a user enter a city/location and see the next
promising Moon opportunity. Keep the web app first-class. After the web, feed,
and calendar flow is complete and testers show recurring demand, build a
focused iOS/Android companion with Expo.

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

The Expo companion and web app may share contracts, validation, formatting,
domain logic, design rules, assets, and suitable simple components. Complex
views, web semantics and URLs, storage, notifications, permissions, and
distribution stay platform-specific.

Saved places remain device-only. Notifications are local-first. The client may
cache results and perform bounded offline Moon calculations. Weather-backed
scoring remains authoritative in the backend, so an offline calculation cannot
replace a backend-scored opportunity.

The backend should not require accounts for the first lookup endpoint.

## Option 1: Web-First Backend MVP

A small web app and backend let users enter a city/location and see upcoming Moon opportunities without installing an app.

Benefits:

- Lowest trial friction.
- Easy to share with photographers.
- Scoring quality can be validated before mobile work.
- Backend is already justified for weather, geocoding, caching, and scoring.
- RSS/Atom and `.ics` follow-up do not require accounts or personal-data subscriptions.

Costs:

- Web notifications are less reliable than installed-client local notifications.
- Requires hosting from the beginning.
- If email or recurring personal alerts are added, identity, consent, unsubscribe, deletion, and data-retention choices return.

## Option 2: Backendless Installed Client

An installed client calculates ephemeris, fetches weather, scores
opportunities, stores locations locally, and schedules local notifications.

Benefits:

- Minimal infrastructure.
- Strong privacy story.
- No account database.
- No hosting or backend operations.

Costs:

- Weather API keys cannot be protected if a keyed provider is required.
- Scoring and provider fixes require app releases.
- iOS and Android background work may be delayed by platform restrictions.
- Sync, push, email, calendar, and web access are harder later.

## Option 3: Installed Client Plus Small Backend

The app remains local-first, but sends locations and preferences to a backend scoring API. The backend returns ranked Moon opportunities.

Possible first endpoint:

```text
POST /forecast-opportunities
  input: locations + preferences
  output: ranked Moon opportunities
```

Benefits:

- Weather credentials stay server-side.
- Scoring fixes and weather provider migrations can happen without app releases.
- Backend can later add push, email, calendar, remote config, and feature flags.
- Installed clients can stay thinner.

Costs:

- Requires hosting, monitoring, backups, and basic operational discipline.
- Privacy policy and logging boundaries matter immediately.
- More moving parts before the app has proven value.

## Recommended MVP Direction

Use a web-first backend MVP. Weather provider research in `docs/weather-provider-research.md` makes a small backend useful earlier than originally assumed because it can protect paid API keys, cache forecast data, round coordinates before provider calls, and keep scoring/provider changes server-side. A web surface then gives the fastest way to test whether the score is useful.

Recommended boundary:

- Web UI accepts a city/location and displays ranked opportunities.
- Backend geocodes raw Unicode location queries.
- Browser locale is used only as a display/ranking hint.
- Backend fetches and caches weather by rounded coordinate/time bucket.
- Backend computes ranked opportunities from request payloads.
- Backend rate-limits public lookup requests and protects upstream provider quotas.
- Backend records provider call counters and cache hit/miss counts for operational visibility.
- Backend avoids permanent location storage in the first version.
- RSS/Atom feeds and `.ics` export are the first low-friction follow-up options.
- Recurring event-aware matching, such as approximate flight or transport
  patterns, is deferred until the base city lookup and Moon/weather score are
  useful.
- Email alerts remain later because they require storing email plus location preferences.
- Installed-client local notifications remain a later milestone for recurring personal alerts.

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

Home hosting is acceptable for alpha or beta, but it should be treated as
temporary. The current self-hosting boundary is documented in
`docs/self-hosting-alpha-plan.md`.

Preferred exposure:

- Cloudflare DNS and TLS.
- Cloudflare Tunnel instead of raw port forwarding.
- Do not expose unrelated home services.

Feasible early hosts:

- Lenovo Linux laptop for short alpha if sleep is disabled and Ethernet is used.
- Existing Intel NUC for alpha/beta if resource usage is monitored.
- Raspberry Pi 4/5 as a constrained alpha target. The current plan assumes
  32 GB WD Purple SD cards for node-local storage plus roughly 64-80 GB of NFS
  storage from another server. Avoid embedded etcd HA and treat the nodes as
  rebuildable.
- Prefer an x86 mini PC or other SSD-backed host later for Java/Spring
  ergonomics and any durable database.

## Admin/Ops Surface

The backend should include a small private web admin/status surface before public alpha. This is not a full admin product or dashboard; it is an operator view for quota and health.

Recommended first route:

```text
GET /admin/status
```

Protect it with authentication suitable for the deployment environment. The
tester alpha may expose only exact `GET`/`HEAD /admin/status` behind #119's
validated 64-hex token; production should restore a private edge such as
Cloudflare Access.

Minimum fields:

- App version/build.
- Active feature flags, including LLM fictional fallback.
- Provider call counters by provider and endpoint.
- Hourly, daily, and monthly usage estimates where plan limits are known.
- Warning thresholds, such as 50 percent, 80 percent, and 95 percent of known limits.
- Cache hit/miss counts for geocoding, weather, and scoring.
- Recent provider errors and timeouts.
- Current public API rate-limit settings.

Provider quota tracking may need local counters because providers may not expose real-time quota usage. Count outbound calls from the backend and compare them with configured plan limits.

Possible automatic responses:

- Warn admin at 50 percent usage.
- Treat 80 percent usage as operational risk.
- Disable nonessential work near 95 percent, such as LLM Easter eggs or aggressive feed refresh.
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

- Use Expo for a focused iOS/Android companion while retaining the web app.
- Keep canonical geocoding, weather, ephemeris, and scoring in the backend by
  default.
- Treat local notifications, background work, secure storage, permissions,
  backup, and distribution as explicit platform seams.
- Allow bounded offline Moon calculations, but keep weather-backed scoring
  authoritative in the backend.
- Retain the current responsive web UI unless a separately reviewed migration
  demonstrates enough value.

## Key Unresolved Architecture Choices

- First MVP boundary: web-first backend lookup is now favored; first API shape is documented in `docs/api-shape.md`.
- Geocoding provider: Open-Meteo Geocoding is the first candidate for city/town lookup; exact-address autocomplete remains out of scope.
- Internationalized search: raw Unicode input must work even when browser locale is generic or English.
- Ephemeris implementation: Astronomy Engine `2.1.19` is accepted for the JVM
  backend under `docs/ephemeris-research.md`; a future client may perform
  bounded offline Moon calculations, but uses backend weather-backed scoring.
- Weather provider: Open-Meteo is the first candidate; still validate forecast quality and whether alpha use is strictly non-commercial.
- Weather cache: whether to use Postgres immediately or begin with a simpler cache during the first scoring prototype.
- Admin/ops storage: where provider call counters, cache metrics, and recent errors live before a full database exists.
- Identity timing: no identity for one-off lookup; add optional email or anonymous identity only when saved alerts require it.
- Client delivery: Expo is a later iOS/Android companion. It does not replace
  the current web UI.
- Notification timing: RSS/Atom and `.ics` first; email and installed-client
  local notifications later.
- Recurring event context: whether to begin with user-entered approximate
  patterns, curated public patterns, or provider-backed schedules; how to
  represent timing uncertainty, cancellations, and route changes.
- Distribution/community: Reddit is manual/community validation only; Mastodon and Bluesky are not planned.
- Data retention: whether backend request logs may contain coordinates, and how to scrub or minimize them.
- Terrain horizon modeling: deferred until users can provide exact shooting positions; city-level lookup cannot reliably account for hills, buildings, or trees.
- Hosting target for alpha: dev laptop, NUC, Raspberry Pi, x86 mini PC, or
  hosted VPS. Tracked by
  [#19](https://github.com/rapucha/moon-service/issues/19).
