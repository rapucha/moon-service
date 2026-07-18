# MVP Roadmap

Current status: Phase 3, the MVP app loop. The anonymous web lookup, live
geocoding and weather path, natural Moon-window scoring, shareable results, and
responsive opportunity UI are implemented. Phase 4 infrastructure is complete:
the tested application deploys automatically to the Raspberry Pi and the
bounded tester-alpha Funnel is enabled. Follow-up #158 preserves the postponed
household-impact measurement without blocking the alpha. The current product
focus is to show enough ranked candidates for human evaluation, calibrate the
provisional scoring model with photographers under
[#33](https://github.com/rapucha/moon-service/issues/33), and then complete
public feeds and calendar exports under
[#16](https://github.com/rapucha/moon-service/issues/16).

## Phase 0: Planning Baseline

Status: completed.

Deliverables:

- Product notes.
- Architecture notes.
- Scoring model v0.
- MVP roadmap.
- Agent/repo guidance.

Exit criteria:

- The team can choose the first architecture boundary.
- The first implementation step is small and reversible.

## Phase 1: Research Spikes

Status: completed for the current MVP provider and architecture choices.

Goal: resolve the minimum unknowns before scaffolding heavy code.

Tasks:

- Research ephemeris libraries for JVM backend use. The accepted MVP/alpha
  dependency and build policy are documented in `docs/ephemeris-research.md`.
- Validate Astronomy Engine against known Moonrise/Moon altitude/azimuth examples before scaffolding app/backend code. Completed for the thin scoring prototype; results are recorded in `docs/ephemeris-research.md`.
- Research weather providers, including terms, forecast horizon, rate limits, cost, and API key requirements. Initial recommendation is documented in `docs/weather-provider-research.md`.
- Validate Open-Meteo weather field coverage for the scoring model. Completed for the thin scoring prototype; results are recorded in `docs/weather-provider-research.md`.
- Decide whether provider requirements force a backend for the MVP. Current
  answer: yes for real MVP/alpha; a throwaway installed-client prototype could
  call a keyless provider directly, but that is not the product boundary.
- Research geocoding options for city/location input. Initial recommendation is documented in `docs/geocoding-research.md`.
- Validate Open-Meteo geocoding against ambiguous and internationalized city queries. Initial validation is recorded in `docs/geocoding-research.md`; the v0 response to known native-script misses is a curated alias/transliteration fallback before a secondary provider or narrowed v0 promise.
- Draft the first web/API lookup shape for city/location input and opportunity output. Initial shape is documented in `docs/api-shape.md`.

Exit criteria:

- Ephemeris library candidate selected and validation plan documented.
- Weather provider candidate selected and weather cache/privacy plan documented.
- Geocoding provider candidate selected and city/location lookup privacy plan documented.
- First web/API preview contract documented.
- MVP architecture choice documented in `docs/architecture.md`.

## Phase 2: Thin Scoring Prototype

Status: completed. The retained prototypes now support and validate the real
backend rather than representing the active product phase.

Goal: prove the scoring model behind a minimal backend contract without committing to the full app stack.

Recommended web-first path:

- Create a minimal backend endpoint or script-level prototype.
- Accept one city/location or coordinate and a time range.
- Return natural low-Moon windows with window-level weather and light explanations.
- Use fixture weather data before integrating a live provider if useful.
- Add a tiny web page only after the endpoint shape is clear.

Possible installed-client path:

- After the web, feed, and calendar flow is complete and testers show recurring
  demand, build a focused Expo iOS/Android companion. Keep the web app
  first-class. Device-only saved places, local-first notifications, cached
  results, and bounded offline Moon calculations are in scope for that later
  companion. The backend remains authoritative for weather-backed scoring.
- Compute or request natural low-Moon windows.
- Display ranked opportunities.
- Skip account, push, and sync.

Exit criteria:

- One real location can produce plausible opportunities.
- Score explanations are understandable.
- Scoring defects can be fixed without broad rewrites.

## Phase 3: MVP App Loop

Status: in progress. The anonymous lookup loop is implemented; empirical
ranking validation plus feeds and calendar exports remain.

Goal: make the simplest useful public loop work.

Tasks:

- Enter a city or location on the webpage.
- Fetch ranked opportunities.
- Display details for each opportunity.
- Provide a shareable result URL.
- Add clear privacy copy for geocoding, weather lookup, coordinate rounding, and backend logs.
- Provide RSS/Atom feed output for public opportunities.
- Provide dynamic public `.ics` calendar feeds for canonical real locations.
- Provide `.ics` export for individual opportunities.

Exit criteria:

- A user can enter a location and get a useful next-opportunity result.
- A user can follow a public feed or export an event without creating an account.
- No mandatory account exists.
- Backend, if present, does not permanently store location data by default.

## Phase 4: Alpha Operations

Status: completed for the tester-alpha infrastructure under
[#93](https://github.com/rapucha/moon-service/issues/93) and
[#97](https://github.com/rapucha/moon-service/issues/97). Logging, provider
quota monitoring, tested multi-architecture image publication, automatic
Raspberry Pi deployment, rollback, and the bounded public Funnel are active.
The controlled household-impact measurement remains a nonblocking follow-up in
[#158](https://github.com/rapucha/moon-service/issues/158).

Goal: run a small tester alpha.

Tasks:

- Add basic logging and error reporting.
- Add provider quota monitoring if using a backend.
- Provision the dedicated Raspberry Pi 4 reproducibly from Raspberry Pi OS
  Lite 64-bit/Trixie with Ansible, Docker Engine, and Compose.
- Pull successfully published ARM64 images by immutable digest, verify
  revision-aware readiness, and automatically retain/restore a known-good
  rollback generation.
- Keep loopback as the safe host default, allow an explicit primary-IPv4
  listener on the operator's trusted home LAN, and use Tailscale Funnel for the
  first tester-facing HTTPS boundary under #97; do not forward a raw router
  port.
- Keep the 32 GB SD-card host rebuildable with bounded logs/images and no
  durable application data. Document off-host secret and host recovery.

Exit criteria:

- Alpha can run for a few users without manual daily intervention.
- Known privacy and operational risks are documented.

## Remaining MVP Work

Near-term implementation and decision work is tracked in GitHub issues rather
than only in this roadmap:

- [#33](https://github.com/rapucha/moon-service/issues/33): empirically
  calibrate scoring with photographer judgments and real observations.
- [#16](https://github.com/rapucha/moon-service/issues/16): add public feeds
  and iCalendar exports for real opportunities.

Open supporting follow-ups:

- [#158](https://github.com/rapucha/moon-service/issues/158): measure household
  impact during one controlled outside-household Funnel burst.
- [#3](https://github.com/rapucha/moon-service/issues/3): extensible scoring
  context for future interests and recurring events.
- [#5](https://github.com/rapucha/moon-service/issues/5): decouple controller
  tests from provider identity details.
- [#109](https://github.com/rapucha/moon-service/issues/109): documented the
  later Expo iOS/Android companion strategy. It coexists with the first-class
  web app.

Completed MVP foundations include:

- [#93](https://github.com/rapucha/moon-service/issues/93): continuously deploy
  the self-hosted tester alpha.
- [#97](https://github.com/rapucha/moon-service/issues/97): expose and verify
  the tester alpha through Tailscale Funnel.
- [#17](https://github.com/rapucha/moon-service/issues/17): accept Astronomy
  Engine `2.1.19` from JitPack under the alpha build constraints.
- [#14](https://github.com/rapucha/moon-service/issues/14): Open-Meteo weather
  integration.
- [#15](https://github.com/rapucha/moon-service/issues/15): web lookup and
  shareable results.
- [#67](https://github.com/rapucha/moon-service/issues/67): separate browser
  source ownership from backend resources while preserving Spring Boot's
  public static surface.
- [#18](https://github.com/rapucha/moon-service/issues/18): v0 scoring policy.
- [#19](https://github.com/rapucha/moon-service/issues/19): alpha hosting and
  backup boundary.
- [#27](https://github.com/rapucha/moon-service/issues/27): containerized live
  smoke test.
- [#7](https://github.com/rapucha/moon-service/issues/7): decide bot identity
  policy for agent-created pull requests.
- [#8](https://github.com/rapucha/moon-service/issues/8): add basic
  provider-call scalability protections.
- [#9](https://github.com/rapucha/moon-service/issues/9): add basic backend
  observability.
- [#87](https://github.com/rapucha/moon-service/issues/87): return up to ten
  anonymous ranked candidates while scoring remains uncalibrated.

## Deferred Features

- Mandatory accounts.
- Installed iOS/Android client.
- Cross-device sync.
- Push notifications.
- Email alerts.
- Calendar OAuth and private calendar integration.
- Recurring event-aware scoring and subscriptions, including specific flights,
  transport routes, public event patterns, or user-defined weekly schedules.
- Automated Reddit posting.
- Mastodon/Bluesky integration.
- Map planning.
- Terrain/horizon modeling.
- Landmark alignment and saved compositions.
- Billing.

## Current Next Product Step

The anonymous web loop now returns up to ten raw ranked candidates while
preserving explicit caller control on the direct scoring contract. This is a
discovery safeguard, not a scoring calibration.

The next product step is [#33](https://github.com/rapucha/moon-service/issues/33):
collect a small, inspectable set of photographer judgments and real observation
cases, then change scoring only where that evidence supports it.

The calibration-feedback initiative is delivered in this order:

1. Document the purpose, privacy and storage boundary, accepted feedback-loss
   risk, evidence governance, and roadmap under
   [#162](https://github.com/rapucha/moon-service/issues/162).
2. Document the exact API, timing, validation, UUID, historical-preview, and
   browser contracts under
   [#163](https://github.com/rapucha/moon-service/issues/163).
3. Add disabled optional persistence with migrations, idempotency, the row
   cap, and database-isolation tests.
4. Add the capability and final-submission API with validation, admission,
   stable errors, and the logging boundary.
5. Add bounded historical astronomy reconstruction and local-time resolution.
6. Add immediate recommendation reviews and reverse observations in the web
   UI.
7. Add an explicit, bounded browser save-for-review queue.
8. Provision disabled private PostgreSQL on NFS with the accepted alpha risk.
9. Wire the application to the private database without making lookup or
   readiness depend on it.
10. Add private statistics, deterministic export, and confirmed deletion tools.

Every implementation capability stays disabled until its prerequisites and
safe availability behavior exist. After all required implementation children
merge, controlled host activation proves lookup and readiness with PostgreSQL
stopped before enabling feedback. Collection begins only after that check.

The owner later decides when evidence is sufficient. A separate reviewed child
curates selected cases into an authored corpus. Scoring and window-selection
changes are separate children and exist only when that corpus supports them.
If it supports no behavior change, record provisional acceptance, calibration
gaps, and remaining uncertainty before closing #33. The parent remains open
through collection and corpus curation.

After the core recommendations prove useful, complete public feeds and
calendar exports under
[#16](https://github.com/rapucha/moon-service/issues/16). The JitPack dependency
decision and safeguards required before public deployment are recorded in
[#17](https://github.com/rapucha/moon-service/issues/17).

## Implemented Backend And Prototype History

The geocoding/API contract mismatch found during provider validation is now resolved in documentation:

- `docs/geocoding-research.md` defines raw Open-Meteo lookup first, then a small curated alias/transliteration fallback for known misses such as `東京`, `京都`, `大阪`, `とうきょう`, and `서울`.
- `docs/api-shape.md` includes input validation, abuse protection, alias messaging, and one-character place-name handling.
- The current v0 decision is to keep the broad raw Unicode input promise with curated fallback for known provider gaps, without adding a secondary geocoder or LLM/translation API by default.

The retained script-level geocoding spike is `scripts/geocoding_contract_spike.py`. It should stay small and independent from app/backend scaffolding while the lookup contract is still being proven.

Use it to verify:

- Raw hits, ambiguous results, alias hits, one-character handling, invalid input, not-found behavior, and fictional-location separation.
- Normalized candidate/output examples matching `docs/api-shape.md`.
- Provider drift with optional live Open-Meteo calls when network access is available.

The retained script-level scoring spike is `scripts/scoring_contract_spike.py`. It uses fixture Moon, Sun, and weather samples to prove hard filters, score components, ranking, explanation text, and API-shaped output before real ephemeris/weather integration. It is now a historical contract spike; the next scoring model should replace sampled opportunity slices with natural low-Moon windows.

The retained thin real-data scoring spike is `scripts/real_data_scoring_spike.py`. It wires one resolved Prague fixture to live JPL Horizons Moon/Sun samples and live Open-Meteo hourly weather, then reuses the scoring functions. Moon illumination contributes to scoring, but crescent Moon windows are allowed when altitude, light, and weather are otherwise promising. Its weather-provider result remains relevant because Open-Meteo's useful cloud-cover fields are hourly.

The first replacement step now exists as `prototypes/jvm-ephemeris/MoonWindowPrototype.java`. It uses Astronomy Engine on the JVM to sample Moon/Sun positions, emit low-Moon candidate windows, and apply fixture-weather scoring for the Prague validation fixture, without Spring Boot, persistence, live weather calls, feeds, or calendar generation. Its sampling-based window generation should now be treated as prototype scaffolding, not the desired product model.

The JVM prototype now mirrors the retained Python scoring contract for the core top-level, location, opportunity, rejected, and message fields. It still includes prototype-only diagnostics such as sample counts and search interval metadata.

The next narrow step now exists as `prototypes/jvm-scoring/`. It keeps the same prototype boundary, uses Astronomy Engine through Maven/JitPack, ports scoring and candidate generation into small testable Java classes, and adds fixture tests for scoring plus one contract-shaped Prague response.

Contract parity as of this step:

- `scripts/scoring_contract_spike.py` remains the fixed-fixture scoring contract reference for field shape, scoring vocabulary, hard filters, rejection reasons, and exposure-balance labels.
- `prototypes/jvm-ephemeris/MoonWindowPrototype.java` and `prototypes/jvm-scoring/` now match for the Prague real-ephemeris fixture, except for `generatedAt` and the prototype identifier.
- Exact top opportunity timing and score differ between the Python spike and JVM prototypes by design because the Python spike uses synthetic Moon/Sun windows while the JVM prototypes sample Astronomy Engine.
- `scripts/prototype_contract_parity.py` verifies the shared contract shape and vocabulary across all three, and verifies exact source-file JVM vs Maven JVM parity after normalizing volatile prototype metadata.

The Maven prototype also accepts a request-shaped JSON fixture at `prototypes/jvm-scoring/fixtures/prague-preview-request.json`. This proves the future web/API input boundary without adding HTTP routes, geocoding integration, live weather, feeds, calendar generation, or backend scaffolding.

The first HTTP contract work has moved from the former Spring preview harness
into the real `backend/` module. The current backend exposes
`POST /api/opportunities/search` with the same fixture request shape, reusing
the Maven scoring prototype through the `jvm-scoring-prototype` Maven
dependency and its public `PreviewEvaluator` facade behind a backend-owned
opportunity search seam.

The query-shaped endpoint now uses resolved coordinates, elevation, timezone,
and provider metadata for opportunity generation instead of requiring a
prototype fixture location ID. The direct fixture endpoint remains Prague-only
for deterministic scoring/prototype checks.

The query-shaped endpoint also uses the backend Open-Meteo weather provider
seam for normalized hourly forecast facts when `moon.weather.provider=open-meteo`
is configured. Maven tests keep this network-free with provider fixtures and
fake weather providers; direct live provider drift checks live under
`live-tests/`.

The backend now locks down the first invalid-request behavior: malformed JSON,
non-object JSON, unsupported fixture locations, invalid start dates, and
out-of-range numeric controls return HTTP `400` with
`status: "invalid_request"`.

Natural-window scoring refactor now implemented in `prototypes/jvm-scoring/`
and exercised through the backend:

- Generate natural visible-Moon recommendation windows inside
  Moonrise-to-Moonset passes, using crossings through the configured altitude
  ceiling and pass peak while preserving passes across local midnight.
- Low Moon remains best, but context Moon and high-context Moon windows can now
  rank when ambient light, illumination balance, and weather are favorable.
- Use sampling only as a numerical aid if needed to bracket event crossings.
- Removed public and fixture dependence on `stepMinutes` as an
  opportunity-shaping control.
- Use hourly Open-Meteo weather fields for V0 because cloud cover is the key
  scoring input and cloud-cover layers are hourly.
- The resolved-location backend path can score with live hourly forecast facts;
  deeper weather-change interval splitting and merging can be refined after the
  first adapter is in place.
- Returned windows are selected by top `limit`; request-level `minScore` was
  removed from the Maven and Spring prototype contract.
