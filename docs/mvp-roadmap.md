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
- Return candidate windows with score explanations.
- Use fixture weather data before integrating a live provider if useful.
- Add a tiny web page only after the endpoint shape is clear.

Possible Android path:

- Create a minimal Android prototype later with one saved location.
- Compute or request candidate windows.
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

## Deferred Features

- Mandatory accounts.
- Native Android app.
- Cross-device sync.
- Push notifications.
- Email alerts.
- Calendar integration.
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

The retained script-level scoring spike is `scripts/scoring_contract_spike.py`. It uses fixture Moon, Sun, and weather samples to prove hard filters, score components, ranking, explanation text, and API-shaped output before real ephemeris/weather integration.

Next, either capture any live-provider adjustments found by the spikes, or move on to a thin real-data scoring prototype that wires one resolved location to ephemeris and weather provider calls without scaffolding the full backend.
