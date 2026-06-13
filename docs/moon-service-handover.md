# Moon Photography Alert Service - Handover

Date: 2026-06-13

This handover summarizes the planning conversation so a new Codex CLI session on the Lenovo Linux laptop can continue without needing the original chat transcript.

## Project Idea

Build a service/app for photographers that warns them about upcoming photogenic Moon opportunities near selected locations.

The desired moment is roughly:

- The Moon is visible while ambient light is still bright enough that a well-exposed Moon can fit into the scene dynamic range.
- The Moon is close to the horizon, so it can be photographed near a house, church, hill, tree, skyline, or other foreground subject.
- Weather is promising: blue sky, partly cloudy, textured clouds, or otherwise visually interesting conditions; reject overcast or bad visibility.
- Users choose locations they care about.
- The system checks ephemeris and weather forecasts ahead of time and notifies the user.

The inspiration is The Photographer's Ephemeris, but the goal is a much simpler Android-friendly / alert-first version, not a full clone.

## Current Product Direction

The most promising early direction is not a full web service plus Android app from day one. It is a privacy-friendly Android-first or hybrid model with a very small backend.

Important product stance:

- Avoid forcing accounts unless there is clear user value.
- Avoid "account sludge" for a simple alert tool.
- A device-bound identity is attractive for the Android app.
- Email identity should be optional, not mandatory, unless the user wants email alerts, backup, or calendar features.
- Calendar integration is a likely later milestone.

Possible user-facing positioning:

> No account required. Your locations and alert settings live on this device by default. Add email only if you want backup, email alerts, or calendar integration.

## Architecture Options Discussed

### Fully Backendless Android App

Android app does everything:

- stores locations locally
- calculates Sun/Moon ephemeris locally
- fetches weather directly from a public API
- scores candidate Moon windows
- schedules local notifications
- uses Android Auto Backup where available

Pros:

- very simple infrastructure
- no account database
- good privacy story
- no hosting needed
- fewer security concerns

Cons:

- urgent scoring/weather-provider fixes require app updates
- users may not update quickly
- background work on Android can be delayed by battery restrictions
- weather API keys cannot be protected if a paid/keyed provider is used
- cross-device sync, email, calendar, and web access become harder

### Small Backend Control Plane

Android app is still the main client, but backend owns the fast-changing logic:

- scoring rules
- weather integration
- candidate event generation
- remote config / feature flags
- provider migration
- optional push/email/calendar later

Possible first endpoint:

```text
POST /forecast-opportunities
  input: locations + preferences
  output: ranked Moon opportunities
```

This can avoid accounts at first. The app sends locations/preferences and receives scored opportunities. The backend does not need to permanently store user locations unless push subscriptions or cloud sync are introduced.

Pros:

- server-side fixes are immediate
- app can stay thin
- no mandatory account system
- provider API keys can be protected
- can later add device identity and push

Cons:

- requires hosting
- requires operational care
- privacy story needs clear boundaries

Current best compromise:

```text
Android app:
  - UI
  - local saved locations
  - local notifications
  - optional local ephemeris preview

Small backend:
  - weather integration
  - Moon opportunity scoring
  - remote config
  - candidate generation
  - later: push/email/calendar
```

## Authentication / Identity Direction

Avoid mandatory accounts initially.

Preferred model:

- Android install creates anonymous device/user identity.
- App stores credentials locally.
- Backend can associate push token and subscriptions with that identity later.
- Android Auto Backup can help restore the credential where available.
- Provide an explicit recovery code from day one or early.
- Email magic link can be optional later for backup, email notifications, and calendar.

Important caveat:

- Device-only identity without recovery becomes hostile when users lose phones, reinstall, or clear app data.
- Design recovery early, even if it is just "save this recovery code."

Android backup notes:

- Android Auto Backup can help, but not all users have Google backup enabled or available.
- Some users run Android without Google account/services.
- Google backup should be treated as convenience, not the only recovery path.
- If push uses Firebase Cloud Messaging, normal push also assumes Google Play services on typical Android devices.
- Do not back up volatile FCM push tokens. Re-register them after restore.
- It is acceptable if backup restores the same user/subscription identity to a new phone, as long as hardware-specific tokens are renewed.

## Notifications Direction

Potential notification channels:

- Local Android notifications for backendless or hybrid MVP.
- Push notifications later, probably via Firebase Cloud Messaging.
- Email later or optionally from the start if web-only beta is chosen.
- Calendar integration later via .ics attachment/export first, then Google/Microsoft calendar OAuth if worthwhile.

If email is used:

- ISP SMTP may be acceptable for early beta if authenticated SMTP, TLS, rate limits, and SPF/DKIM/DMARC can be configured.
- Do not run a home SMTP server directly.

## Hosting Direction

Home hosting is acceptable for alpha/beta.

Home hosting pros:

- very low cost
- easy to iterate
- enough traffic capacity for early users

Home hosting cons:

- power/router/ISP outages
- security responsibility
- backups and monitoring required
- public service coupled to home infrastructure

Preferred exposure:

- Use Cloudflare DNS/TLS.
- Prefer Cloudflare Tunnel over raw port forwarding.
- Do not expose home automation, MQTT, OpenHAB, Pi-hole, or InfluxDB.

Hardware notes:

- Raspberry Pi 4/5 with 4-8 GB RAM and SSD is feasible.
- Avoid microSD-only storage for Postgres.
- A cheap x86 mini PC is often better than an RPi for Java/Spring due to x86 Docker image compatibility and more normal performance.
- Existing Intel NUC Celeron J4005, 8 GB RAM, SSD is feasible for alpha/beta, even with OpenHAB, Pi-hole, MQTT, and InfluxDB, but watch RAM and storage writes.
- Lenovo dev laptop can also host first alpha if sleep is disabled and Ethernet is used. It has battery-as-mini-UPS, but mixing dev chaos and public hosting should not last too long.

## Development Machine Direction

Best main development machine among the available old workstations:

1. Lenovo i5 Linux Mint, 16 GB RAM, SSD, Ethernet: best overall dev machine.
2. Windows 10 Phenom II X6, 36 GB RAM, SSD: useful secondary box, but old CPU.
3. i3 Mac mini, 16 GB RAM, Fusion Drive: least suitable due to Fusion Drive and likely Android Studio/macOS limitations.

Linux Mint is acceptable and probably the right choice for the Lenovo.

Suggested local tooling:

- JDK 21
- Maven
- Git
- Docker Engine + Docker Compose plugin
- Android Studio
- Android SDK command-line tools
- physical Android phone for testing if emulator is too heavy
- Postgres via Docker Compose
- Codex CLI

Docker is recommended over Podman initially because Spring Boot Docker Compose support, Testcontainers, IDE integrations, and project docs assume Docker more often. Podman is possible but may add compatibility work.

## Stack Direction

Original stack idea:

- Java + Spring Boot backend
- Kotlin Android app
- Postgres database

This stack still fits, especially if a backend control plane exists.

Possible later backend components:

- Spring Boot REST API
- Postgres
- Flyway or Liquibase migrations
- scheduled jobs
- weather provider integration
- ephemeris/scoring engine
- notification sender
- remote config / feature flags

Possible Android components:

- Kotlin
- Jetpack Compose
- Room/DataStore for local persistence
- WorkManager for background refresh
- local notifications
- optional FCM later
- optional Android Auto Backup

## Weather / Ephemeris Direction

The core scoring problem is to define "spectacular" precisely.

Initial scoring inputs:

- Moon altitude: low above horizon, perhaps 0-12 degrees.
- Moon azimuth: useful for direction hints and later subject alignment.
- Moon illumination: full or near-full for first mode, but allow crescent modes later.
- Sun altitude: daylight/civil twilight/golden-hour relation to Moon visibility and dynamic range.
- Cloud cover: reject overcast, prefer clear or textured partial clouds.
- Precipitation probability: low.
- Visibility: above threshold.
- Forecast confidence: surface uncertainty rather than pretending certainty.

First useful MVP can ignore exact landmark alignment:

- user saves location
- backend/app finds promising Moon windows
- user receives "good Moon opportunity" with time, Moon altitude/azimuth, Sun state, and weather summary

Later advanced features:

- map view
- azimuth corridor
- shooting position vs subject position
- focal length/composition planning
- terrain/horizon elevation
- skyline/building obstruction
- saved compositions, not just saved locations

## Issue Tracking / Repo Hosting

Use GitHub for the project unless there is a strong reason not to.

Reasons:

- private repos on free plan
- Issues and Projects
- GitHub Actions
- Dependabot
- common collaborator accounts
- strong Codex/Git tooling fit

Jira is overkill.

Suggested lightweight labels:

```text
type:bug
type:feature
type:research
type:infra
type:ux
area:backend
area:android
area:weather
area:ephemeris
area:auth
priority:p1
priority:p2
priority:p3
```

Suggested docs:

```text
docs/product-notes.md
docs/architecture.md
docs/scoring-model.md
docs/mvp-roadmap.md
```

## MCP / Codex Tooling Direction

Do not add many MCP servers initially.

Useful early:

- GitHub MCP if working with GitHub issues/PRs from Codex.

Maybe later:

- Postgres MCP after schema exists and if DB inspection is useful.
- Supabase MCP only if Supabase is selected.
- Linear/Jira/Slack only if those tools are actually used.

Avoid early:

- production DB MCP with broad write access
- cloud-provider/Kubernetes MCPs
- secrets-manager MCP unless there is a real need

## Codex Notes

The Linux Codex CLI is enough for agentic coding.

In CLI:

- There is no separate "add project" step.
- The project is effectively the current working directory.
- Start with `cd /path/to/repo` and then `codex`.
- Codex stores sessions locally and supports `codex resume`.
- Use `codex resume --last` to continue the most recent session for the current directory.
- Use `/status` inside Codex to see thread ID, context usage, and rate limits.

Codex context:

- Threads must fit in the model context window.
- Codex can compact context automatically.
- Important project decisions should be written into repo docs rather than relying only on chat history.
- Durable project rules should go into `AGENTS.md` once the repo exists.

Recommended reasoning level:

- Moderate for product/architecture brainstorming.
- High for auth/privacy/recovery, ephemeris math, scoring model, database model, and release reviews.
- Low/moderate for mechanical edits.

## Suggested First Milestone

Do not build everything yet. First milestone should answer the MVP architecture question with minimal code.

Proposed first concrete tasks:

1. Create GitHub private repo.
2. Create initial docs:
   - `docs/product-notes.md`
   - `docs/architecture.md`
   - `docs/scoring-model.md`
   - `docs/mvp-roadmap.md`
   - `AGENTS.md`
3. Decide first MVP architecture:
   - Android-only backendless prototype, or
   - Android + small backend scoring API.
4. Research/select ephemeris library for Kotlin/JVM or backend Java.
5. Research/select weather API and terms/rate limits.
6. Define scoring model v0 in docs before writing heavy code.
7. Scaffold only after scoring/API boundaries are clear.

## Suggested Prompt For The Next Codex CLI Session

Paste this after copying the handover into the new repo:

```text
Read docs/moon-service-handover.md and treat it as the project handover from a previous planning session.

First, summarize the current product direction and identify the key unresolved architecture choices.
Then create or update these repo docs:
- AGENTS.md
- docs/product-notes.md
- docs/architecture.md
- docs/scoring-model.md
- docs/mvp-roadmap.md

Do not scaffold backend or Android code yet. Keep this as a planning/documentation pass.
When done, propose the smallest next implementation step.
```

## Linux Laptop Startup Steps

If starting from no repo:

```bash
mkdir -p ~/dev/moon-service
cd ~/dev/moon-service
git init
mkdir -p docs
```

Copy this handover file into the repo:

```text
docs/moon-service-handover.md
```

Then start Codex:

```bash
cd ~/dev/moon-service
codex
```

Inside Codex, paste the prompt from "Suggested Prompt For The Next Codex CLI Session".

If the repo already exists on GitHub:

```bash
cd ~/dev
git clone git@github.com:YOUR_USER/YOUR_REPO.git
cd YOUR_REPO
mkdir -p docs
```

Copy this handover to:

```text
docs/moon-service-handover.md
```

Then:

```bash
codex
```

and paste the suggested prompt.

Optional direct one-shot:

```bash
codex "Read docs/moon-service-handover.md. Summarize the project direction, create the initial docs listed in the handover, and do not scaffold code yet."
```

