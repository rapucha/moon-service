# Architecture

## Current Product Shape

The leading architecture is Android-first with a small backend control plane. The Android app owns the user experience and local data; the backend owns fast-changing integrations and scoring logic.

```text
Android app
  - saved locations
  - preferences
  - opportunity list UI
  - local notifications
  - optional local ephemeris preview

Small backend
  - weather provider integration
  - Moon opportunity candidate generation
  - scoring rules
  - remote config
  - later push/email/calendar support
```

The backend should not require accounts for the first scoring endpoint.

## Option 1: Backendless Android App

The Android app calculates ephemeris, fetches weather, scores opportunities, stores locations locally, and schedules local notifications.

Benefits:

- Minimal infrastructure.
- Strong privacy story.
- No account database.
- No hosting or backend operations.

Costs:

- Weather API keys cannot be protected if a keyed provider is required.
- Scoring and provider fixes require app releases.
- Android background work may be delayed by battery restrictions.
- Sync, push, email, calendar, and web access are harder later.

## Option 2: Android Plus Small Backend

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
- Android app can stay thinner.

Costs:

- Requires hosting, monitoring, backups, and basic operational discipline.
- Privacy policy and logging boundaries matter immediately.
- More moving parts before the app has proven value.

## Recommended MVP Direction

Use a hybrid architecture unless provider research shows that a backendless prototype is clearly simpler and acceptable.

Recommended boundary:

- Android stores user-owned data locally.
- Backend computes ranked opportunities from request payloads.
- Backend avoids permanent location storage in the first version.
- Local notifications can be scheduled from opportunities returned by the backend.
- Push notifications remain a later milestone.

## Hosting Direction

Home hosting is acceptable for alpha or beta, but it should be treated as temporary.

Preferred exposure:

- Cloudflare DNS and TLS.
- Cloudflare Tunnel instead of raw port forwarding.
- Do not expose unrelated home services.

Feasible early hosts:

- Lenovo Linux laptop for short alpha if sleep is disabled and Ethernet is used.
- Existing Intel NUC for alpha/beta if resource usage is monitored.
- Raspberry Pi 4/5 with SSD, or preferably an x86 mini PC for Java/Spring ergonomics.

## Expected Future Stack

Backend:

- Java 21.
- Spring Boot REST API.
- Postgres.
- Flyway or Liquibase.
- Scheduled jobs once push/email exists.
- Weather provider client.
- Ephemeris and scoring engine.

Android:

- Kotlin.
- Jetpack Compose.
- Room or DataStore.
- WorkManager.
- Local notifications.
- Optional Firebase Cloud Messaging later.
- Optional Android Auto Backup.

## Key Unresolved Architecture Choices

- First MVP boundary: backendless Android prototype or Android plus stateless scoring API.
- Ephemeris implementation: Kotlin/JVM library shared with Android, Java backend library, or separate implementations.
- Weather provider: API quality, cost, forecast horizon, rate limits, terms, and key protection requirements.
- Identity timing: no identity at first, anonymous device identity from first release, or defer until push/sync.
- Notification timing: local notifications only for MVP, or early backend-driven push.
- Recovery model: recovery code from day one, optional email later, and Android Auto Backup as convenience only.
- Data retention: whether backend request logs may contain coordinates, and how to scrub or minimize them.
- Hosting target for alpha: dev laptop, NUC, Raspberry Pi, x86 mini PC, or hosted VPS.
