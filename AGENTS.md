# Moon Service Agent Notes

## Project Purpose

Moon Service is a lightweight discovery and alert tool for photographers. It should identify upcoming Moon photography opportunities near user-selected locations, with emphasis on low Moon altitude, useful ambient light, and promising weather.

The near-term product is a web-first discovery MVP that helps users decide when to go outside with a camera, with recurring alerts added only after the basic value is proven.

## Current Phase

This repo is moving from planning/prototype mode into a thin real backend
spine. Narrow prototypes remain under `prototypes/`, and the first real Spring
Boot backend module lives under `backend/`.

Do not scaffold an installed client, database, accounts, or new live provider
integration code until the relevant MVP boundaries are documented. The backend
should remain small and web-first: replace fixture-backed seams with geocoding,
weather, caching, feeds, and `.ics` behavior deliberately.

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
- Installed client later: saved locations, local preferences, and local
  notifications only after the web value is proven. React Native with Expo is
  the leading cross-platform candidate to evaluate, not a selected stack;
  track the decision in [#109](https://github.com/rapucha/moon-service/issues/109).

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
- Avoid adding public production constructors, factories, or methods only to
  make tests shorter. Keep production API surface aligned with real runtime
  use. Put test-only construction convenience in test helpers or builders
  unless there is a concrete production caller or established local pattern.
- State technical judgment directly. Agreement should include reasoning; disagreement should be plain and actionable.
- Do not introduce mandatory accounts without documenting user value and recovery behavior.
- Do not permanently store user locations server-side unless saved alerts require it and the privacy model is updated.
- Design device identity recovery before relying on anonymous device-bound accounts.
- Treat platform backup, background scheduling, and push services as
  conveniences with iOS/Android assumptions, not universal guarantees.
- If subagents, delegation, or parallel agent work may help a session, ask the
  user near the beginning of that session for explicit permission to use
  subagents. Treat this as a request for active-session authorization, not as
  an override of any runtime, tool, sandbox, or external-model approval rules.
- When parallel branches or worktrees are active and a branch needs manual
  verification without being checked out in the primary workspace, run that
  branch from its isolated worktree on an available loopback port. Verify
  readiness and a representative user path, then report the branch, port, and
  a direct clickable URL instead of asking the user to switch branches. Keep
  the preview running until verification is complete and stop it afterward.
  Bind to `127.0.0.1` by default; use broader network exposure only when the
  user explicitly needs it. Report startup blockers rather than silently
  omitting the verification link.
- Treat user wording as intentional. If the user phrases a request as a
  question or feasibility check, such as starting with "can you", "could you",
  "is it possible", "should we", or ending with a question mark, answer the
  question first and do not make code, documentation, GitHub, or other mutating
  changes yet. Begin implementation only after the user gives a clear
  imperative instruction, such as "do it", "go ahead", "implement", "update",
  or "create". If the wording is ambiguous, ask what outcome is required before
  changing files or external state.
- Use GitHub issues as the source of truth for actionable implementation work, technical debt, follow-ups, and decision tasks. Product and architecture docs should capture strategy and decisions, but the next implementation step should come from an open issue unless the user explicitly asks for exploratory work first.
- Use the existing `enhancement` and `documentation` labels for feature and docs work. Use `mvp`, `tech-debt`, `decision`, `blocked`, and `follow-up` labels when they clarify issue triage.
- For issue-backed implementation work, use a branch name that mentions the issue number, preferably `issue-<number>-short-topic`, and update the issue to link to the branch where the work is being done.
- Merge issue-backed implementation work through a pull request; do not merge implementation branches directly.
- Pull requests must mention the issue or issues they address. Completed implementation issues should be closed through, or at least explicitly link to, the pull request that delivered the work.
- Agent-authored pull requests should assign `rapucha` and request review from
  `rapucha` when created. Use `gh pr create --assignee rapucha --reviewer
  rapucha ...`; the repository workflow also applies this to non-draft PRs
  opened by `moon-service-agent`.
- Session handover files are transient working notes for context resets, laptop shutdowns, or other session-boundary handoffs. Do not commit them by default; commit one only when explicitly useful as durable project state. Prefer replacing or removing old handovers instead of accumulating them.
- At the end of implementation tasks, stage all intended source, test, and documentation changes with `git add` so they are ready for commit. Leave unrelated, generated, IDE-only, or otherwise intentionally excluded files unstaged, and call them out in the final response.
- In this environment, `git push` requires network access and sandboxed DNS has repeatedly failed. When the user asks to push any branch or remote, run the push with escalated permissions immediately instead of first attempting a sandboxed push.
- Do not repeat a failing command, API request, or tool call unchanged unless the failure is plausibly transient, such as a timeout, network interruption, rate-limit retry hint, lock contention, or service restart. For deterministic errors, change the request based on a concrete hypothesis, reduce it to a minimal reproduction, inspect docs/help/output, or stop and explain the blocker. For plausibly transient errors, retry with exponential backoff and a small retry budget; once the next backoff delay would reach roughly 30 to 60 seconds, stop retrying and report the failure.

## Change Scope and Pull Request Sizing

Before issue-backed implementation, record the intended coherent outcome,
independently reviewable concerns, likely files, estimated non-generated changed
lines, and which proposed PR owns each acceptance criterion. Examples of
independent concerns include backend behavior, frontend UX,
deployment/operations, CI/automation, and provider/privacy policy. Code, tests,
and documentation that support one behavior do not become separate concerns
merely because they live in different file types.

A proposed PR crosses the scope gate when any of these is true:

- More than two independently reviewable concerns or subsystems.
- More than 12 non-generated changed files.
- More than 800 non-generated added-plus-deleted lines.

Tests and supporting documentation count toward the file and line limits.
Generated, vendored, and lock files do not count toward the numeric gate, but
the plan and PR must disclose them.

Before editing an oversized plan, use a fresh read-only planning agent after
the user has authorized subagents for the active session. The reviewer must
return `single_pr` or `split_required`, map acceptance criteria to proposed
PRs, identify dependencies, and recommend merge order. If a planning agent is
unavailable or not authorized, pause oversized work and report the blocker;
do not silently waive the review.

Default to `split_required` whenever a gate is crossed. Keeping oversized work
in one PR requires an inseparability or safety rationale recorded on the issue
and explicit approval from the user or repository owner. For split work,
create and link child issues before implementation, keep the parent issue open,
give each PR one coherent outcome, and make every slice independently safe and
mergeable. Leave unfinished capabilities disabled by default.

Re-evaluate the gate during implementation. If the actual diff crosses it or
a new independently reviewable concern appears, stop and split instead of
silently expanding the current PR.

Before opening a nontrivial implementation PR, stage the complete intended
diff and ask a fresh read-only agent to review it against repository
instructions and relevant contracts. Triage every finding, fix accepted
findings narrowly, record reasons for rejected or deferred findings, rerun
relevant checks, and summarize the review outcome in the PR.

Treat implementation work as nontrivial when it changes runtime behavior,
public contracts, scoring or data transformation, provider/privacy/security
boundaries, deployment or CI behavior, migrations, or multiple files with
coupled behavior. Tiny mechanical edits and wording-only documentation changes
may skip the staged-diff review, but the PR must record why review was not
required.

## Suggested Tooling Direction

As implementation continues, the expected stack remains:

- Backend: Java, Spring Boot, Postgres, Flyway or Liquibase.
- Installed client: undecided. Evaluate React Native/Expo against the existing
  web UI and native-platform requirements under issue #109 before scaffolding.
- Local infrastructure: Docker Compose for Postgres and integration dependencies.

Do not add Postgres, migrations, an installed client, or local infrastructure
until the next implementation step explicitly calls for them.

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
