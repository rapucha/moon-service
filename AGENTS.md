# Moon Service Agent Notes

## Project Purpose

Moon Service is a lightweight discovery and alert tool for photographers. It should identify upcoming Moon photography opportunities near user-selected locations, with emphasis on low Moon altitude, useful ambient light, and promising weather.

The near-term product is not a full Photographer's Ephemeris clone. It is a web-first discovery MVP that helps users decide when to go outside with a camera, with recurring alerts added only after the basic value is proven.

## Current Phase

This repo is moving from planning/prototype mode into a thin real backend
spine. Narrow prototypes remain under `prototypes/`, and the first real Spring
Boot backend module lives under `backend/`.

Do not scaffold Android, database, deployment, accounts, or live provider
integration code until the relevant MVP boundaries are documented. The backend
should remain small and web-first: start by replacing fixture-backed seams with
geocoding, weather, caching, feeds, and `.ics` behavior deliberately.

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

- `docs/README.md`: human-facing documentation hub.
- `docs/ai-agent/README.md`: active AI-agent operating guide, context packs, and checklists.
- `docs/product-notes.md`: product stance, users, MVP scope, privacy posture.
- `docs/architecture.md`: architecture options, recommended hybrid shape, unresolved decisions.
- `docs/api-shape.md`: first web/API contract, statuses, result kinds, localStorage, RSS/Atom, and `.ics` rules.
- `docs/scoring-model.md`: first scoring model for Moon opportunities.
- `docs/ephemeris-research.md`: ephemeris library recommendation and validation plan.
- `docs/weather-provider-research.md`: weather provider recommendation, caching, and privacy notes.
- `docs/geocoding-research.md`: geocoding provider recommendation and city/location lookup privacy notes.
- `docs/mvp-roadmap.md`: milestone plan and implementation order.
- `prototypes/jvm-scoring/`: minimal Maven JVM scoring/ephemeris prototype with fixture tests.
- `backend/`: first Spring Boot backend module, currently fixture-backed through the scoring prototype.

## Engineering Guidelines

- Keep early changes narrow and documentation-led.
- Prefer explicit tradeoffs over premature abstractions.
- State technical judgment directly. Agreement should include reasoning; disagreement should be plain and actionable.
- Do not introduce mandatory accounts without documenting user value and recovery behavior.
- Do not permanently store user locations server-side unless saved alerts require it and the privacy model is updated.
- Design device identity recovery before relying on anonymous device-bound accounts.
- Treat Android Auto Backup and Firebase Cloud Messaging as conveniences with platform assumptions, not universal guarantees.
- Use GitHub issues as the source of truth for actionable implementation work, technical debt, follow-ups, and decision tasks. Product and architecture docs should capture strategy and decisions, but the next implementation step should come from an open issue unless the user explicitly asks for exploratory work first.
- Use the existing `enhancement` and `documentation` labels for feature and docs work. Use `mvp`, `tech-debt`, `decision`, `blocked`, and `follow-up` labels when they clarify issue triage.
- For issue-backed implementation work, use a branch name that mentions the issue number, preferably `issue-<number>-short-topic`, and update the issue to mention the branch where the work is being done.
- Merge issue-backed implementation work through a pull request; do not merge implementation branches directly.
- Pull requests must mention the issue or issues they address. Completed implementation issues should be closed through, or at least explicitly link to, the pull request that delivered the work.
- Session handover files are transient working notes for context resets, laptop shutdowns, or other session-boundary handoffs. Do not commit them by default; commit one only when explicitly useful as durable project state. Prefer replacing or removing old handovers instead of accumulating them.
- At the end of implementation tasks, stage all intended source, test, and documentation changes with `git add` so they are ready for commit. Leave unrelated, generated, IDE-only, or otherwise intentionally excluded files unstaged, and call them out in the final response.
- In this environment, `git push` requires network access and sandboxed DNS has repeatedly failed. When the user asks to push any branch or remote, run the push with escalated permissions immediately instead of first attempting a sandboxed push.
- Do not repeat a failing command, API request, or tool call unchanged unless the failure is plausibly transient, such as a timeout, network interruption, rate-limit retry hint, lock contention, or service restart. For deterministic errors, change the request based on a concrete hypothesis, reduce it to a minimal reproduction, inspect docs/help/output, or stop and explain the blocker. For plausibly transient errors, retry with exponential backoff and a small retry budget; once the next backoff delay would reach roughly 30 to 60 seconds, stop retrying and report the failure.

## Suggested Tooling Direction

As implementation begins, the expected stack remains:

- Backend: Java, Spring Boot, Postgres, Flyway or Liquibase.
- Android: Kotlin, Jetpack Compose, Room or DataStore, WorkManager, local notifications.
- Local infrastructure: Docker Compose for Postgres and integration dependencies.

Do not add Postgres, migrations, Android, or local infrastructure until the
next implementation step explicitly calls for them.

## Verification

For documentation-only changes, verify with:

```bash
git diff --check
```

For backend code changes, include the focused module tests below.

For the current JVM ephemeris/scoring prototype, after fetching the documented
jars into `/tmp`, verify with:

```bash
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar prototypes/jvm-ephemeris/MoonWindowPrototype.java --location prague-cz --start 2026-06-29 --days 7 --step-minutes 30 --min-score 50 --limit 5
```

For ordinary backend changes, verify with:

```bash
mvn test -pl backend -am
```

For the Maven-based JVM scoring prototype, verify scoring changes with:

```bash
(cd prototypes/jvm-scoring && mvn test)
(cd prototypes/jvm-scoring && mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.classpathScope=test -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json")
```

Run prototype parity only when changing scoring/window generation, comparing migration behavior, or retiring a prototype:

```bash
python3 -B scripts/prototype_contract_parity.py
```
