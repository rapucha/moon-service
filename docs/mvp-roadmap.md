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
- Research weather providers, including terms, forecast horizon, rate limits, cost, and API key requirements.
- Decide whether provider requirements force a backend for the MVP.
- Draft the `POST /forecast-opportunities` request/response shape if a backend is selected.

Exit criteria:

- Ephemeris library candidate selected and validation plan documented.
- Weather provider candidate selected.
- MVP architecture choice documented in `docs/architecture.md`.

## Phase 2: Thin Scoring Prototype

Goal: prove the scoring model without committing to full app/backend structure.

Possible backend-first path:

- Create a minimal JVM scoring module or Spring Boot endpoint.
- Accept one location and a time range.
- Return candidate windows with score explanations.
- Use fixture weather data before integrating a live provider if useful.

Possible Android-first path:

- Create a minimal Android prototype with one saved location.
- Compute or request candidate windows.
- Display ranked opportunities.
- Skip account, push, and sync.

Exit criteria:

- One real location can produce plausible opportunities.
- Score explanations are understandable.
- Scoring defects can be fixed without broad rewrites.

## Phase 3: MVP App Loop

Goal: make the simplest useful user loop work.

Tasks:

- Save locations locally.
- Fetch or compute ranked opportunities.
- Display details for each opportunity.
- Schedule local notifications for selected alert thresholds.
- Add clear privacy copy for local storage and any backend requests.

Exit criteria:

- A user can save a location and receive a useful local notification.
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
- Cross-device sync.
- Push notifications.
- Email alerts.
- Calendar integration.
- Map planning.
- Terrain/horizon modeling.
- Landmark alignment and saved compositions.
- Billing.

## Smallest Next Implementation Step

Before scaffolding app/backend code, finish the ephemeris validation spike:

Use the cases in `docs/ephemeris-research.md` to compare Astronomy Engine against JPL Horizons, then record the observed differences and decide whether the library is accepted for the prototype.
