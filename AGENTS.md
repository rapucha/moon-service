# Moon Service Agent Notes

## Project Purpose

Moon Service is a lightweight discovery and alert tool for photographers. It should identify upcoming Moon photography opportunities near user-selected locations, with emphasis on low Moon altitude, useful ambient light, and promising weather.

The near-term product is not a full Photographer's Ephemeris clone. It is a web-first discovery MVP that helps users decide when to go outside with a camera, with recurring alerts added only after the basic value is proven.

## Current Phase

This repo is in planning and documentation mode with narrow prototypes under
`prototypes/`.

Do not scaffold production backend, Android, database, or deployment code until
the MVP architecture, scoring model, ephemeris library, and weather provider
choices are documented. A thin Spring Boot HTTP contract harness is allowed
under `prototypes/` when it stays fixture-only and avoids production concerns.

## Product Direction

- Prefer no mandatory account for the MVP.
- Start with a zero-install web flow: enter a city/location and see the next good Moon opportunity.
- Keep the first public product anonymous: web lookup, shareable result page, RSS/Atom, and `.ics` export.
- Treat email alerts as later because they require storing personal data and handling consent, unsubscribe, deletion, retention, and provider processing.
- Treat Reddit as a community experiment only; do not depend on automated posting.
- Do not plan Mastodon or Bluesky integration for now.
- Keep privacy boundaries explicit if a backend receives locations or preferences.
- Favor a small backend because geocoding, weather lookup, caching, scoring, and provider migration are server-side concerns.

## Architecture Bias

The current likely direction is:

- Web MVP: city/location entry, next opportunity display, shareable result page, RSS/Atom feeds, and `.ics` export.
- Small backend: geocoding, weather integration, scoring rules, weather cache, candidate Moon opportunity generation, and provider abstraction.
- Android later: saved locations, local preferences, local notifications, and possibly local preview calculations.

The main unresolved choice is now the exact first web/API contract for city lookup, opportunity results, RSS/Atom feeds, and `.ics` export.

## Documentation Map

- `docs/moon-service-handover.md`: historical planning handover from the previous session.
- `docs/product-notes.md`: product stance, users, MVP scope, privacy posture.
- `docs/architecture.md`: architecture options, recommended hybrid shape, unresolved decisions.
- `docs/api-shape.md`: first web/API contract, statuses, result kinds, localStorage, RSS/Atom, and `.ics` rules.
- `docs/scoring-model.md`: first scoring model for Moon opportunities.
- `docs/ephemeris-research.md`: ephemeris library recommendation and validation plan.
- `docs/weather-provider-research.md`: weather provider recommendation, caching, and privacy notes.
- `docs/geocoding-research.md`: geocoding provider recommendation and city/location lookup privacy notes.
- `docs/mvp-roadmap.md`: milestone plan and implementation order.
- `prototypes/jvm-scoring/`: minimal Maven JVM scoring/ephemeris prototype with fixture tests.
- `prototypes/spring-preview/`: thin Spring Boot HTTP contract harness for the preview request/response shape.

## Engineering Guidelines

- Keep early changes narrow and documentation-led.
- Prefer explicit tradeoffs over premature abstractions.
- Do not introduce mandatory accounts without documenting user value and recovery behavior.
- Do not permanently store user locations server-side unless saved alerts require it and the privacy model is updated.
- Design device identity recovery before relying on anonymous device-bound accounts.
- Treat Android Auto Backup and Firebase Cloud Messaging as conveniences with platform assumptions, not universal guarantees.
- After reading each user task, assess whether subagents would materially help. Say so when independent research, review, verification, or disjoint implementation slices could run in parallel; also say when the task is too small or tightly coupled to benefit. Do not spawn subagents unless the user explicitly authorizes subagent/delegated work for that task.
- In this environment, `git push` requires network access and sandboxed DNS has repeatedly failed. When the user asks to push any branch or remote, run the push with escalated permissions immediately instead of first attempting a sandboxed push.
- Do not repeat a failing command, API request, or tool call unchanged unless the failure is plausibly transient, such as a timeout, network interruption, rate-limit retry hint, lock contention, or service restart. For deterministic errors, change the request based on a concrete hypothesis, reduce it to a minimal reproduction, inspect docs/help/output, or stop and explain the blocker. For plausibly transient errors, retry with exponential backoff and a small retry budget; once the next backoff delay would reach roughly 30 to 60 seconds, stop retrying and report the failure.

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

For the current JVM ephemeris/scoring prototype, after fetching the documented
jars into `/tmp`, verify with:

```bash
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar prototypes/jvm-ephemeris/MoonWindowPrototype.java --location prague-cz --start 2026-06-29 --days 7 --step-minutes 30 --min-score 50 --limit 5
```

For the Maven-based JVM scoring prototype, verify with:

```bash
(cd prototypes/jvm-scoring && mvn test)
(cd prototypes/jvm-scoring && mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json")
python3 -B scripts/prototype_contract_parity.py
(cd prototypes/jvm-scoring && mvn install)
(cd prototypes/spring-preview && mvn test)
```
