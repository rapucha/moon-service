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
- Installed client later: keep the web app first-class. After the web, feed,
  and calendar flow is complete and testers show recurring demand, use Expo
  for a focused iOS/Android companion. Share contracts, validation, formatting,
  domain logic, design rules, assets, and suitable simple components. Keep
  complex views, web semantics and URLs, storage, notifications, permissions,
  and distribution platform-specific. Saved places stay on device; notifications
  are local-first; results may be cached; bounded offline Moon calculations are
  allowed. Weather-backed scoring remains authoritative in the backend.

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
- `.agents/review-policy.md`: canonical review gates, triggers, measures, and
  planning-estimate rules.
- `prototypes/jvm-scoring/`: minimal Maven JVM scoring/ephemeris prototype with fixture tests.
- `backend/`: first Spring Boot backend module, currently fixture-backed through the scoring prototype.

## Engineering Guidelines

- Keep early changes narrow and documentation-led.
- Prefer explicit tradeoffs over premature abstractions.
- Avoid adding public production constructors, factories, or methods only to
  make tests shorter. Keep production API surface aligned with real runtime
  use. Put test-only construction convenience in test helpers or builders
  unless there is a concrete production caller or established local pattern.
- State technical judgment directly. Agreement should include reasoning;
  disagreement should be plain and actionable.
- Use plain language in agent-authored issue and pull-request text, GitHub
  comments and replies, review summaries, commit messages, code comments, and
  project or agent-policy documentation. Put one main idea in a sentence. Use
  common, concrete words and short paragraphs when they stay exact. Keep
  technical terms when they add needed precision. Avoid chains of nouns,
  formal filler, abstract lead-ins, and decorative metaphors. When flagging
  hard-to-read text, suggest simpler wording. Keep required template fields,
  but write their content like a colleague. This is a review rule, not a word
  limit, readability score, or style-linter requirement.
- Code comments should explain why code exists or why a choice was made. Do not
  restate obvious code. Prefer one or two direct sentences when that is enough.
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
- For visual or document-format work where readability, layout, rendering, or
  format remains unresolved, distinguish a temporary local preview from durable
  PR-ready delivery. Within an already-authorized task, an affirmative format
  selection authorizes only the smallest useful preview; questions and
  feasibility checks remain non-mutating. Keep previews untracked, normally
  under `/tmp`, use only already-available tooling without installs, downloads,
  manifests, or dependencies, avoid external mutations, and validate only
  enough to make the preview trustworthy. Preview acceptance approves the
  visual, not durable repository mutation or publication; that outcome must
  already be authorized or explicitly confirmed, after which every normal
  issue, branch, review, generated-output, push, and pull-request gate applies.
  Do not add a redundant preview pause when durable delivery was explicit and
  the visual choice was already settled.
- Use GitHub issues as the source of truth for actionable implementation work, technical debt, follow-ups, and decision tasks. Product and architecture docs should capture strategy and decisions, but the next implementation step should come from an open issue unless the user explicitly asks for exploratory work first.
- Use the existing `enhancement` and `documentation` labels for feature and docs work. Use `mvp`, `tech-debt`, `decision`, `blocked`, and `follow-up` labels when they clarify issue triage.
- For issue-backed implementation work, use a branch name that mentions the issue number, preferably `issue-<number>-short-topic`, and update the issue to link to the branch where the work is being done.
- Merge issue-backed implementation work through a pull request; do not merge implementation branches directly.
- Pull requests must mention the issue or issues they address. Completed implementation issues should be closed through, or at least explicitly link to, the pull request that delivered the work.
- Agent-authored pull requests should assign `rapucha` and request review from
  `rapucha` when created. Use `gh pr create --assignee rapucha --reviewer
  rapucha ...`; the repository workflow also applies this to non-draft PRs
  opened by `moon-service-agent`.
- Session handover files are transient working notes for context resets, laptop
  shutdowns, or other session-boundary handoffs. When creating or updating one,
  preserve context from the current conversation and existing tool results; do
  not perform a fresh GitHub audit, rerun tests, or investigate already-known
  state unless explicitly requested. Label uncertain details instead. Keep the
  handover concise: capture the current objective, decisions and rationale,
  pending feedback, active work, blockers, next actions, and files that must not
  be disturbed. Do not commit handovers by default; commit one only when
  explicitly useful as durable project state. Prefer replacing or removing old
  handovers instead of accumulating them.
- At the end of implementation tasks, stage all intended source, test, and documentation changes with `git add` so they are ready for commit. Leave unrelated, generated, IDE-only, or otherwise intentionally excluded files unstaged, and call them out in the final response.
- In this environment, `git push` requires network access and sandboxed DNS has repeatedly failed. When the user asks to push any branch or remote, run the push with escalated permissions immediately instead of first attempting a sandboxed push.
- Do not repeat a failing command, API request, or tool call unchanged unless the failure is plausibly transient, such as a timeout, network interruption, rate-limit retry hint, lock contention, or service restart. For deterministic errors, change the request based on a concrete hypothesis, reduce it to a minimal reproduction, inspect docs/help/output, or stop and explain the blocker. For plausibly transient errors, retry with exponential backoff and a small retry budget; once the next backoff delay would reach roughly 30 to 60 seconds, stop retrying and report the failure.

## Agent Review Workflows

The canonical definitions of project-specific review skills live under
`.agents/skills/`. Change them through the repository's normal issue and pull
request workflow. Treat copies outside the repository as temporary installed
artifacts, not as an independently editable source of truth.

The review numbers, their effects, and the rules for planning estimates live in
[`.agents/review-policy.md`](.agents/review-policy.md). Any review skill that
applies those rules must read that file. Do not repeat its numbers in
`AGENTS.md` or a skill.

A skill migration is not complete while a same-named legacy copy remains
discoverable outside the repository. After the repository-local version reaches
the default branch, remove or disable the legacy copy and verify in a fresh
Codex session that only the canonical path is offered. Until then, do not edit
the legacy copy independently or claim migration cleanup is complete.

### Review-Agent Callsigns

When reporting delegated project reviews to the user, use these role callsigns:

- **Grady Booch** — `issue-design-review`
- **Martin Fowler** — `implementation-scope-review`
- **Dennis Ritchie** — `second-agent-review`
- **Bruce Schneier** — `sensitive-information-review`

Each review must still use the fresh AI subagent required by its skill. These
callsigns do not imply that the named people participate in, endorse, or supply
views to the project.

Before an agent creates a nontrivial actionable issue or treats its own draft
as implementation authority, use `$issue-design-review` with a fresh read-only
agent after the user has authorized subagents for the active session. This
includes implementation, technical-debt, follow-up, decision, dependency,
privacy, and operational issues. The review must return `ready`, `revise`, or
`split_required`; record the verdict or a concise review summary in the issue
when it is created. Do not publish or act on a `revise` draft, and split a
`split_required` draft according to the repository workflow. Tiny bookkeeping
issues that directly transcribe an explicit user instruction may skip this
review only when the issue records why it was unnecessary.

An issue is not `ready` while it silently introduces or leaves unresolved a
material runtime component, edge proxy, provider, external account, stored-data
or privacy obligation, network exposure, deployment burden, recurring cost, or
operational dependency. Document the need and alternatives and obtain the
required owner decision before implementation planning.

If implementation would add or materially change one of these dependencies and
the source issue does not already document and approve it, stop before editing.
Update the issue through the authorized workflow, rerun `$issue-design-review`,
and rerun `$implementation-scope-review` when the accepted scope or PR packaging
may change.

## Change Categories and Gates

Use [`.agents/review-policy.md`](.agents/review-policy.md) for change
categories, concern and file gates, code-file limits, documentation triggers,
output budgets, counting rules, and required evidence.

Before issue-backed implementation, record the accepted outcome, independently
reviewable concerns or subsystems, expected paths, other paths that may be
needed and why, an expected ordinary-file count range, informational
ordinary-churn estimate, expected code-file results, documentation measures,
output classes, and acceptance-criterion ownership.

Likely paths and count ranges forecast the implementation. They are not a fixed
list and do not authorize more work. A different path or count does not need
reauthorization or another scope review by itself. It must still serve the
accepted outcome and stay within the accepted concerns, dependencies, output
classes, and hard gates. Record the actual paths and count after staging.
Explain any meaningful difference from the plan.

Select the change category from accepted authority before editing. Do not
relabel work later to obtain a larger allowance. Ambiguous or mixed work uses
the stricter applicable gate or splits. Room under a limit never authorizes an
unaccepted concern.

Ordinary churn is information. Documentation size conditions are review
triggers, not scope gates. Apply the documentation authority rules and staged
review requirements in the policy.

## Scope Growth and Mutation Authority

Do not use room under a limit to enlarge accepted work. Stop before editing and
obtain new scope authority when accepted behavior cannot be delivered safely
without an unrequested refactor, incidental fix, new concern, or new dependency.
In particular:

- Omit an unrequested refactor when the accepted behavior can be delivered
  safely without it. If it is genuinely required, explain why and re-estimate.
- Report an incidental finding without fixing or filing it when it does not
  block correctness, safety, or validation. A blocking finding requires
  stop-and-replan authority.
- Add an abstraction, production API, configuration option, provider slot,
  toggle, or extension point only for a current accepted production use or an
  established local boundary. Tests alone and speculative future use are not
  sufficient.
- Exclude opportunistic formatting, renames, dead-code removal, dependency
  upgrades, cleanup, and unrelated hardening tests or documentation.
- Stop before adding or changing a manifest, lock file, workflow, provider,
  account, network service, runtime component, build tool, or test dependency
  unless the accepted issue explicitly approves that dependency and its
  consequences.

A `split_required` verdict does not itself authorize GitHub mutations. After the
owner explicitly accepts an enumerated split plan, an agent may create only the
child issues that directly map the parent acceptance criteria named in that
plan. Incidental findings, cleanup, speculative work, and new dependencies still
require separate explicit user/owner instruction or explicit source-issue
authority. Without that authority, report the observation without mutating
GitHub.

Before editing a plan that may cross a scope gate, use
`$implementation-scope-review` with a fresh read-only agent after the user has
authorized subagents for the active session. The reviewer must return
`single_pr` or `split_required`, map acceptance criteria to proposed PRs,
identify dependencies, and recommend merge order. If a planning agent is
unavailable or not authorized, pause oversized work and report the blocker; do
not silently waive the review.

Documentation review thresholds are not scope gates. Crossing one does not by
itself require another scope review or a split.

Default to `split_required` whenever a hard gate is crossed. Hard gates cover
concerns, ordinary files, resulting code-file size, and the separate generated,
vendored, and lock-file budgets. Keeping oversized work in one PR requires the
exact exceeded gate and measured values, an inseparability or safety rationale
recorded on the issue, and explicit approval from the user or repository owner.
General approval of an issue or PR is not a blanket exception. For an
owner-accepted split plan, create and link only its authorized child issues
before implementation, keep the parent issue open, give each PR one coherent
outcome, and make every slice independently safe and mergeable. Leave unfinished
capabilities disabled by default.

Re-evaluate hard gates during implementation. If the actual diff crosses one or
a new independently reviewable concern, output category, or dependency appears,
stop and rerun `$implementation-scope-review`. Split the work unless keeping one
PR satisfies the recorded exception requirements above; never silently expand
or recategorize the current PR. If a documentation threshold is crossed, record
the measures and arrange the focused staged review without rerunning scope
review for size alone.

Before opening or finalizing a nontrivial implementation PR, stage the complete
intended diff and use `$second-agent-review` with a fresh read-only agent. Triage
every finding, fix accepted findings narrowly, record reasons for rejected or
deferred findings, rerun relevant checks, and summarize the review outcome in
the PR.

Before any agent-authored push, use `$sensitive-information-review` with a fresh
read-only agent on the exact source ref, actual remote destination, complete
refspecs, and push options. Inspect all commit messages and every introduced or
modified blob version in the outgoing range, including intermediate versions
absent from the final diff. Git LFS pointer blobs require inspection of their
referenced payloads. Do not substitute a net diff or broaden the review into
unrelated local secrets and uncommitted work.

Before an agent creates a nontrivial PR or publishes a relevant agent-authored
mutation to its surface, use the same skill on the exact unpublished title,
body, relevant comment or reply, and
attachment bytes plus their intended repository and PR destination. Settled
Git and PR-surface inputs may be reviewed together before their corresponding
publication steps. Before PR creation, prove complete merge-base-to-head
coverage from recorded full object IDs and reachability; inspect any commits or
blobs not covered by matching `clear` pre-push reviews. For an existing PR,
treat its live text and discussion only as read-only context and review only the
new outgoing material. Review attachments, including PDFs and other documents,
before upload; review a later outgoing reference to a service-generated URL as
part of that later text.

Immediately before each publication step, re-resolve the applicable refs,
refspecs, push options, destination, and exact unpublished bytes. A change to an
unpublished reviewed input invalidates the verdict and requires a fresh review;
publishing those exact inputs does not. A later PR mutation gets its own
pre-publication review of only its new outgoing content. Do not run a mandatory
sensitive-information review after publication or merely for final handoff. At
handoff, verify refs, checks, and PR state read-only and report the recorded
pre-publication verdicts without another PR mutation.

The sensitive-information reviewer returns `clear`, `review_required`, or
`block`. A `block` verdict stops the applicable push or PR publication step. Treat
`review_required` as unresolved until the owner decides or full coverage is
restored; never present it as a passed gate. Report candidates only through
opaque review-local IDs and sanitized locations, never by reproducing or
fingerprinting the value. Missing document tooling, encryption, malformed or
unsupported content, unsafe extraction, or exceeded inspection bounds prevents
a `clear` result. The reviewer never executes active content or performs
remediation. Pre-commit use is optional and occurs only when explicitly
requested; it does not replace mandatory pre-push or outgoing-PR review. If a
credible sensitive value is discovered after publication, stop further
publication and report the existing owner-directed incident actions rather than
describing the retrospective audit as a gate.

Treat implementation work as nontrivial when it changes runtime behavior,
public contracts, scoring or data transformation, provider/privacy/security
boundaries, deployment or CI behavior, migrations, or multiple files with
coupled behavior. Tiny mechanical edits and wording-only documentation changes
may skip the staged-diff review only when the documentation authority and size
rules do not require it. The PR must record why review was not required.

## Suggested Tooling Direction

As implementation continues, the expected stack remains:

- Backend: Java, Spring Boot, Postgres, Flyway or Liquibase.
- Installed client: a later focused Expo iOS/Android companion that keeps the
  web app first-class. Do not scaffold it until the web, feed, and calendar
  flow is complete and testers show recurring demand.
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
