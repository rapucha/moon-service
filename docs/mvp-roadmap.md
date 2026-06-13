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
- Validate Astronomy Engine against known Moonrise/Moon altitude/azimuth examples before scaffolding app/backend code.
- Research weather providers, including terms, forecast horizon, rate limits, cost, and API key requirements. Initial recommendation is documented in `docs/weather-provider-research.md`.
- Decide whether provider requirements force a backend for the MVP. Current answer: yes for real MVP/alpha, though direct Android calls are acceptable for a throwaway prototype.
- Research geocoding options for city/location input. Initial recommendation is documented in `docs/geocoding-research.md`.
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

Before scaffolding app/backend code, finish two validation spikes:

- Use the cases in `docs/ephemeris-research.md` to compare Astronomy Engine against JPL Horizons, then record the observed differences and decide whether the library is accepted for the prototype.
- Build a no-code or script-level Open-Meteo query for one saved location and confirm the fields needed by `docs/scoring-model.md` are present for the target forecast horizon.
- Build a no-code or script-level Open-Meteo geocoding query for several ambiguous cities and confirm the result fields needed by `docs/geocoding-research.md`.
- Review `docs/api-shape.md` once against the validation spikes, then start the smallest backend prototype only if the contract still holds.
