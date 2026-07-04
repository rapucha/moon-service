# MVP Roadmap

## Phase 0: Planning Baseline

Status: current phase.

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

Goal: resolve the minimum unknowns before scaffolding heavy code.

Tasks:

- Research ephemeris libraries for Kotlin/JVM and Java backend use. Initial recommendation is documented in `docs/ephemeris-research.md`.
- Validate Astronomy Engine against known Moonrise/Moon altitude/azimuth examples before scaffolding app/backend code. Completed for the thin scoring prototype; results are recorded in `docs/ephemeris-research.md`.
- Research weather providers, including terms, forecast horizon, rate limits, cost, and API key requirements. Initial recommendation is documented in `docs/weather-provider-research.md`.
- Validate Open-Meteo weather field coverage for the scoring model. Completed for the thin scoring prototype; results are recorded in `docs/weather-provider-research.md`.
- Decide whether provider requirements force a backend for the MVP. Current answer: yes for real MVP/alpha, though direct Android calls are acceptable for a throwaway prototype.
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

Goal: prove the scoring model behind a minimal backend contract without committing to the full app stack.

Recommended web-first path:

- Create a minimal backend endpoint or script-level prototype.
- Accept one city/location or coordinate and a time range.
- Return natural low-Moon windows with window-level weather and light explanations.
- Use fixture weather data before integrating a live provider if useful.
- Add a tiny web page only after the endpoint shape is clear.

Possible Android path:

- Create a minimal Android prototype later with one saved location.
- Compute or request natural low-Moon windows.
- Display ranked opportunities.
- Skip account, push, and sync.

Exit criteria:

- One real location can produce plausible opportunities.
- Score explanations are understandable.
- Scoring defects can be fixed without broad rewrites.

## Phase 3: MVP App Loop

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

Goal: run a small private alpha.

Tasks:

- Add basic logging and error reporting.
- Add provider quota monitoring if using a backend.
- Choose alpha hosting: laptop, NUC, mini PC, Raspberry Pi with SSD, or VPS.
- Prefer Cloudflare Tunnel if home-hosted.
- Document backup and restore behavior.

Exit criteria:

- Alpha can run for a few users without manual daily intervention.
- Known privacy and operational risks are documented.

## Tracked MVP Backlog

Near-term implementation and decision work is tracked in GitHub issues rather
than only in this roadmap:

- [#14](https://github.com/rapucha/moon-service/issues/14): integrate
  Open-Meteo weather forecasts into opportunity scoring.
- [#15](https://github.com/rapucha/moon-service/issues/15): build the first
  web lookup flow and shareable result page.
- [#16](https://github.com/rapucha/moon-service/issues/16): add public feeds
  and iCalendar exports for real opportunities.
- [#17](https://github.com/rapucha/moon-service/issues/17): decide whether
  Astronomy Engine through JitPack is acceptable before real backend adoption.
- [#18](https://github.com/rapucha/moon-service/issues/18): define and test v0
  scoring policy.
- [#19](https://github.com/rapucha/moon-service/issues/19): choose the alpha
  hosting and backup boundary before deployment-specific work.
- [#27](https://github.com/rapucha/moon-service/issues/27): add a
  containerized backend live smoke test.

Existing supporting follow-ups:

- [#3](https://github.com/rapucha/moon-service/issues/3): extensible scoring
  context for future interests and recurring events.
- [#5](https://github.com/rapucha/moon-service/issues/5): decouple controller
  tests from provider identity details.
- [#7](https://github.com/rapucha/moon-service/issues/7): decide bot identity
  policy for agent-created pull requests.
- [#8](https://github.com/rapucha/moon-service/issues/8): add basic
  provider-call scalability protections.
- [#9](https://github.com/rapucha/moon-service/issues/9): add basic backend
  observability.

## Deferred Features

- Mandatory accounts.
- Native Android app.
- Cross-device sync.
- Push notifications.
- Email alerts.
- Calendar integration.
- Recurring event-aware scoring and subscriptions, including specific flights,
  transport routes, public event patterns, or user-defined weekly schedules.
- Automated Reddit posting.
- Mastodon/Bluesky integration.
- Map planning.
- Terrain/horizon modeling.
- Landmark alignment and saved compositions.
- Billing.

## Smallest Next Implementation Step

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
