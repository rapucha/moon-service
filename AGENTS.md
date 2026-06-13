# Moon Service Agent Notes

## Project Purpose

Moon Service is an Android-first alert tool for photographers. It should identify upcoming Moon photography opportunities near user-selected locations, with emphasis on low Moon altitude, useful ambient light, and promising weather.

The near-term product is not a full Photographer's Ephemeris clone. It is an alert-first MVP that helps users decide when to go outside with a camera.

## Current Phase

This repo is in planning and documentation mode.

Do not scaffold backend, Android, database, or deployment code until the MVP architecture, scoring model, ephemeris library, and weather provider choices are documented.

## Product Direction

- Prefer no mandatory account for the MVP.
- Store user locations and alert preferences locally on Android by default.
- Treat email as optional for backup, email alerts, or calendar features.
- Keep privacy boundaries explicit if a backend receives locations or preferences.
- Favor an Android-first or hybrid architecture with a small backend control plane only where it creates clear value.

## Architecture Bias

The current likely direction is:

- Android app: UI, saved locations, local preferences, local notifications, and possibly local preview calculations.
- Small backend: weather integration, scoring rules, candidate Moon opportunity generation, remote config, and later push/email/calendar support.

The main unresolved choice is whether the first MVP should be fully backendless Android or Android plus a minimal scoring API.

## Documentation Map

- `docs/moon-service-handover.md`: historical planning handover from the previous session.
- `docs/product-notes.md`: product stance, users, MVP scope, privacy posture.
- `docs/architecture.md`: architecture options, recommended hybrid shape, unresolved decisions.
- `docs/scoring-model.md`: first scoring model for Moon opportunities.
- `docs/mvp-roadmap.md`: milestone plan and implementation order.

## Engineering Guidelines

- Keep early changes narrow and documentation-led.
- Prefer explicit tradeoffs over premature abstractions.
- Do not introduce mandatory accounts without documenting user value and recovery behavior.
- Do not permanently store user locations server-side unless the feature requires it and the privacy model is updated.
- Design device identity recovery before relying on anonymous device-bound accounts.
- Treat Android Auto Backup and Firebase Cloud Messaging as conveniences with platform assumptions, not universal guarantees.

## Suggested Tooling Direction

When implementation starts, the expected stack is:

- Backend: Java, Spring Boot, Postgres, Flyway or Liquibase.
- Android: Kotlin, Jetpack Compose, Room or DataStore, WorkManager, local notifications.
- Local infrastructure: Docker Compose for Postgres and integration dependencies.

Do not add these tools until the next implementation step explicitly calls for them.

## Verification

For documentation-only changes, verify with:

```bash
git diff --check
```

For future code changes, add focused build/test commands to this file once the project has actual backend or Android modules.
