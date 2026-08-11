# Moon Service Agent Notes

## What Moon Service Does

Moon Service is a small discovery and alert tool for photographers. It should
find upcoming Moon photography opportunities near places that users choose. It
should focus on a low Moon, useful ambient light, and promising weather.

The near-term MVP is a website that helps a user decide when to go outside with
a camera. Add recurring alerts only after this basic service proves useful.

## Current Phase

This repository is moving from plans and prototypes to a small real backend.
Focused prototypes remain under `prototypes/`. The first Spring Boot backend
module lives under `backend/`.

Do not scaffold an installed client, database, account system, or new live
provider integration until its MVP boundaries are documented. The backend
should remain small and web-first. Replace fixture-backed code one part at a
time with geocoding, weather, caching, feeds, and `.ics` support.

## Product Direction

- Prefer not to require an account for the MVP.
- Start with a website that needs no installation: enter a city or location and
  see the next good Moon opportunity.
- Keep the first public product anonymous. It includes web lookup, a shareable
  result page, RSS/Atom feeds, and `.ics` export.
- Add email alerts later. They require personal-data storage, consent,
  unsubscribe and deletion flows, retention rules, and an email provider.
- Use Reddit only for community experiments. Do not depend on automated posts.
- Do not plan Mastodon or Bluesky integration for now.
- State the privacy rules clearly when the backend receives a location or user
  preference.
- Favor a small backend because it handles geocoding, weather lookup, caching,
  scoring, and changes between providers.

## Expected Architecture

The likely architecture is:

- Website: city or location entry, the next opportunity, a shareable result
  page, RSS/Atom feeds, and `.ics` export.
- Small backend: geocoding, weather integration and caching, scoring, candidate
  Moon-opportunity generation, and a provider abstraction.
- Installed app later: keep the website as a complete product. After the web,
  feed, and calendar flows are complete and testers show recurring demand,
  build a focused iOS/Android companion with Expo.
- Share contracts, validation, formatting, domain rules, design rules, assets,
  and simple components where practical. Keep complex views, web URLs and
  behavior, storage, notifications, permissions, and app distribution specific
  to each platform.
- In an installed app, saved places stay on the device and notifications are
  local-first. Results may be cached. Limited offline Moon calculations are
  allowed. Backend scoring remains authoritative when it uses weather data.

The main open architecture decision is the first web/API contract for city
lookup, opportunity results, RSS/Atom feeds, and `.ics` export.

## Documentation Map

- `docs/README.md`: documentation index for people working on the project.
- `docs/ai-agent/README.md`: current agent guide, context packs, and checklists.
- `docs/product-notes.md`: product decisions, users, MVP scope, and privacy
  rules.
- `docs/architecture.md`: architecture choices, current recommendation, and
  open decisions.
- `docs/api-shape.md`: first web/API contract, statuses, result kinds,
  localStorage, RSS/Atom, and `.ics` rules.
- `docs/scoring-model.md`: first scoring model for Moon opportunities.
- `docs/ephemeris-research.md`: recommended ephemeris library and validation
  plan.
- `docs/weather-provider-research.md`: recommended weather provider, caching,
  and privacy notes.
- `docs/geocoding-research.md`: recommended geocoding provider and privacy rules
  for city and location lookup.
- `docs/mvp-roadmap.md`: milestone plan and implementation order.
- `.agents/review-policy.md`: canonical review gates, triggers, measures, and
  planning-estimate rules.
- `prototypes/jvm-scoring/`: small Maven scoring and ephemeris prototype with
  fixture tests.
- `backend/`: first Spring Boot backend module, which currently gets fixture
  data through the scoring prototype.

## How Agents Should Work

### Do only the work the user approved

- Treat the user's wording as intentional.
- If the user asks a question or checks feasibility, answer first and do not
  change code, documentation, GitHub, or other external state. Examples include
  requests that start with "can you", "could you", "is it possible", or "should
  we", and requests that end with a question mark.
- Start changing things only after a clear instruction such as "do it", "go
  ahead", "implement", "update", or "create".
- If the requested outcome is unclear, ask before changing files or external
  state.
- Use an open GitHub issue to define implementation, technical debt, follow-up,
  and decision work. Product and architecture documents should record strategy
  and decisions, but the next implementation step should come from an open
  issue. The user may explicitly ask for exploratory work without an issue.
- Use `enhancement` and `documentation` for feature and documentation work.
  Add `mvp`, `tech-debt`, `decision`, `blocked`, or `follow-up` when it
  improves triage.

### Keep changes narrow

- Keep early changes small and led by documented decisions.
- Prefer direct tradeoffs to abstractions that have no current need.
- Optimize for the smallest standard implementation that matches the current
  accepted threat model. Do not add defenses for hypothetical future threats
  without explicit owner approval.
- Before creating an implementation issue, present the smallest standard
  approach and any materially stricter alternative. State the current need,
  tradeoffs, and ongoing maintenance cost of the stricter choice.
- Stop and replan if implementation requires a custom protocol client or
  substantially exceeds the forecast because of an added mechanism, concern,
  dependency, output class, or hardening. Informational churn alone remains
  nonblocking. Obtain owner approval for the revised scope before continuing.
- Apply the **Production-Use Evidence** rule in
  [`.agents/review-policy.md`](.agents/review-policy.md) before adding or
  changing a compatibility path, default or fallback behavior, public overload,
  or extension point. An established pattern or boundary alone is not evidence.
  Do not add a production API only to simplify a test. Keep test-only
  convenience in test helpers or builders.
- State technical judgment directly. Agreement should include the reason.
  Disagreement should be plain and actionable.
- Follow [`docs/ai-agent/plain-technical-writing.md`](docs/ai-agent/plain-technical-writing.md)
  for all agent-authored prose. Keep contracts, identifiers, modal verbs, and
  required template fields exact. Suggest simpler wording only when it keeps the
  same meaning. Do not use word limits, readability scores, mechanical counts,
  a style linter, or another automatic gate to enforce the guide.

### Protect the product and its users

- Do not require an account until the project documents the user benefit and
  account-recovery behavior.
- Do not permanently store lookup locations on the server. The only current
  exception is the disabled, bounded, city-level calibration-feedback store
  approved by issue #33 and documented in `docs/product-notes.md` and
  `docs/architecture.md`. Saved alerts need their own updated privacy model.
- Design identity recovery before depending on anonymous device-bound
  accounts.
- Treat platform backup, background scheduling, and push delivery as
  iOS/Android conveniences, not universal guarantees.

### Use subagents and worktrees carefully

- If subagents, delegation, or parallel work may help, use them. If a sandbox,
  active instruction, tool, or other rule requires the user's permission, ask
  first.
- To verify a branch that is checked out in another worktree, run it from that
  worktree on an available loopback port. Check readiness and one representative
  user path. Report the branch, port, and direct clickable URL; do not ask the
  user to switch branches.
- For one-off verification outside the local feedback phase, keep the preview
  running until verification finishes, then stop it. During local feedback,
  use the lifecycle below. Bind to `127.0.0.1` unless the user explicitly needs
  broader access. Report startup blockers instead of omitting the preview link.

### Separate temporary previews from durable work

- This section governs temporary format previews. The local feedback phase
  below governs review of already-authorized durable work.
- When a visual or document format is not settled, distinguish a temporary
  preview from durable work.
- Within a task the user already approved, the user's affirmative format choice
  allows only the smallest useful temporary preview.
- A question or feasibility check does not authorize a preview.
- Keep previews untracked, normally under `/tmp`. Use only installed tools. Do
  not install or download anything, add a manifest or dependency, or mutate
  external state for a preview.
- Validate only enough to make the preview trustworthy.
- Accepting a preview approves the visual choice. It does not authorize a
  repository change or publication. The user must already have requested that
  durable result, or must explicitly request it after accepting the preview.
- Once durable work is authorized, apply every normal issue, branch, review,
  generated-output, push, and pull-request rule.
- Do not add another temporary format-choice pause when the user already
  requested durable work and the visual choice is settled. This does not waive
  the local feedback phase before publication.

### Review implementations locally before publication

Use this phase after durable work is authorized, the implementation plan is
recorded, and any required issue-design and implementation-scope reviews are
complete. It changes publication timing only. Every gate and trigger under
**Review Workflow** remains in force.

- Create the issue-numbered branch locally from the current default branch
  before implementation. Keep it unpushed during feedback and do not create a
  pull request by default.
- Implement only the approved result and run focused local validation for each
  feedback revision.
- Before presenting each local review version, compare the complete diff with
  the smallest direct implementation. Revert any extraction, rename,
  formatting, cleanup, or other structural change that is neither requested
  nor required by accepted behavior, correctness, compilation, or tests.
  Readability preference alone does not authorize the change.
- Host the current unmerged worktree on an available `127.0.0.1` port. The
  hosted state may be staged or unstaged.
- Use the most direct review surface: the application route for runtime or UI
  work, or an installed-tool static or raw-file view for documentation and
  other non-runtime work.
- Keep review hosting ephemeral. Do not install or add a dependency, manifest,
  workflow, service, or tracked preview generator for it.
- Report the branch, revision and staged or unstaged state, port, and direct
  clickable URL. Check readiness and one representative path. Keep the hosted
  version current and running until the owner accepts it, requests publication,
  ends the review, or a session boundary requires a handover. Restore a current
  review surface before resumed feedback work.
- Treat the hosted version and IDE diff as the owner's review version. If a
  trustworthy loopback surface cannot start, report the blocker and do not
  create the pull request unless the owner explicitly says to proceed without
  it.
- Apply feedback locally while it stays inside the accepted result and scope.
  Use the existing stop and re-review triggers when scope changes. Do not run
  final staged implementation review or mandatory publication review after
  each ordinary local feedback revision.
- Publish only after the owner accepts the current review version or explicitly
  requests publication. Silence, preview readiness, completed implementation,
  and passing tests are not acceptance. A later implementation change requires
  another local review and acceptance.
- The owner may explicitly waive the phase or its acceptance pause for one
  named task. Do not infer the waiver or carry it to another task.
- After an accepted change that is major and durable, check only the relevant tracked
  Serena memories before final staged review. The same pull request may correct
  or remove an existing memory without another owner instruction only when the
  accepted change made that memory false or materially incomplete. New memories
  and all other memory edits still require explicit owner instruction.
- After acceptance or an explicit publication instruction, stage the complete
  candidate and follow the existing implementation-review,
  sensitive-information, push, pull-request, CI, and human-review rules. No
  local-feedback step waives a gate.

### Use Git and GitHub consistently

- For issue-backed work, name the branch with the issue number. Prefer
  `issue-<number>-short-topic`. Link the branch from the issue when it is first
  published; do not push it only to create that link.
- Deliver issue-backed implementation through a pull request. Do not merge the
  implementation branch directly.
- Every pull request must mention the issues it addresses. A completed
  implementation issue should be closed through the pull request, or the issue
  should at least link to the pull request.
- An agent-authored pull request should assign `rapucha` and request review
  from `rapucha`. Use `gh pr create --assignee rapucha --reviewer rapucha
  ...`. The repository workflow applies the same rule to non-draft pull
  requests opened by `moon-service-agent`.
- At the end of implementation, stage every intended source, test, and
  documentation change with `git add`. Leave unrelated, generated, IDE-only,
  and intentionally excluded files unstaged. List those files in the final
  response.
- In this environment, run an explicitly requested `git push` with escalated
  permissions immediately. Sandboxed DNS has repeatedly failed.

### Write useful handovers

- Use a session handover only for a context reset, shutdown, or similar session
  boundary.
- Preserve the current conversation and known tool results. Do not perform a
  new GitHub audit, rerun tests, or investigate known state unless the user asks.
- Mark uncertain details as uncertain.
- Record the current goal, decisions and reasons, pending feedback, active work,
  blockers, next actions, and files that must not be disturbed.
- Do not commit a handover by default. Commit it only when it is useful as
  durable project state.
- Prefer replacing or removing old handovers instead of accumulating them.

### Respond to failures deliberately

- Do not repeat a deterministic failure without changing the request or the
  hypothesis. Inspect help or documentation, reduce the problem, or stop and
  explain the blocker.
- Retry only failures that may be temporary, such as timeouts, network
  interruptions, rate limits with retry guidance, lock contention, or service
  restarts.
- Use exponential backoff and a small retry budget. Stop when the next delay
  would be about 30–60 seconds, then report the failure.

## Review Workflow

### Use the repository review files

- The canonical project review skills live in `.agents/skills/`. Change them
  through the normal issue and pull-request workflow. The narrow **In-Place
  Guardrail Repair** rule in [`.agents/review-policy.md`](.agents/review-policy.md)
  allows a focused repair in the implementation pull request where a failure
  surfaced only when `rapucha` explicitly requests it. A copy installed outside
  the repository is temporary and is not an independent source of truth.
- [`.agents/review-policy.md`](.agents/review-policy.md) defines change
  categories, review measures, gates, documentation triggers, output budgets,
  counting rules, planning estimates, required evidence, and their effects. A
  skill must read that file before using those rules. Do not copy the numbers
  into this file or another skill.
- A skill migration remains incomplete while a same-named legacy copy can still
  be discovered outside the repository. After the repository version reaches
  the default branch, remove or disable the legacy copy. Start a fresh Codex
  session and confirm that only the canonical skill appears. Until then, do not
  edit the legacy copy separately or claim the migration is complete.

### Use these review callsigns

- **Grady Booch** — `issue-design-review`
- **Martin Fowler** — `implementation-scope-review`
- **Dennis Ritchie** — `second-agent-review`
- **Bruce Schneier** — `sensitive-information-review`

Each review still requires the fresh AI subagent named by its skill. The real
people named above do not participate in, endorse, or provide the review.

### Review an issue before publishing or using it

- Before an agent creates a nontrivial issue that asks for work or uses its own
  draft as permission to implement, ask a fresh read-only agent to run
  `$issue-design-review`. The user must first authorize subagents for the
  active session.
- Before delegation, compare the draft with the raw user request and established
  authority. Add an **Agent-added scope** section that lists every material
  behavior the drafting agent added, strengthened, or made more specific, its
  source, and whether it is authorized, required for current correctness or
  safety, or unsupported. Write `None` only after making that comparison.
  Report and omit an unsupported nonblocking finding. Stop for an owner decision
  when it blocks the approved work. Do not rely on general approval of a draft
  as authority for an item that was not surfaced in this section.
- Give the reviewer the raw user request and decisions, the complete draft, and
  the authority comparison. An agent-authored parent issue or draft is not a
  substitute for the raw authority.
- This rule covers implementation, technical-debt, follow-up, decision,
  dependency, privacy, and operational issues.
- The reviewer must return `ready`, `revise`, or `split_required`. Record the
  verdict or a short summary in the issue.
- Treat a verdict as invalid when the full review omits a required output
  section, does not independently trace every material criterion to authority,
  or leaves agent-added scope unresolved. Preserve the full review and exact
  reviewed draft as evidence; the short issue record does not replace them.
  Repeat an invalid review or a review followed by a material draft change.
- Do not publish or use a `revise` draft. Follow the repository split workflow
  for `split_required`.
- A tiny bookkeeping issue may skip this review only when it directly records
  an explicit user instruction and the issue explains why review was
  unnecessary.
- Do not mark an issue `ready` while it hides or leaves unresolved a material
  runtime component, edge proxy, provider, external account, stored-data or
  privacy duty, network exposure, deployment work, recurring cost, or
  operational dependency. Document the need and alternatives, then obtain the
  owner's decision before implementation planning.
- If implementation needs to add or materially change one of those dependencies
  and the issue does not approve it, stop before editing. Ask the user to
  approve an issue update. After that approval, update the issue and run
  `$issue-design-review` again. Also run `$implementation-scope-review` again
  when the approved scope or pull-request plan may change.

### Record the implementation plan

- Use [`.agents/review-policy.md`](.agents/review-policy.md) to choose the
  change category and apply concern, file, code-size, documentation, generated,
  vendored, and lock-file rules.
- Before issue-backed implementation, record:
  - the result that the issue or user approved;
  - each independently reviewable concern or subsystem;
  - likely paths and other paths that may be needed, with reasons;
  - the expected ordinary-file range and informational ordinary-churn range;
  - expected resulting code-file sizes;
  - documentation measures and required reviews;
  - output classes, including generated, vendored, and lock-file output; and
  - which pull request owns each acceptance criterion.
- Before editing, map every material behavior change to an accepted result or
  criterion in that plan. If no mapping exists, apply the incidental-finding
  rules below instead of treating the implementation plan as authority.
- Choose the change category from the work that the issue or user approved. Do
  this before editing. Do not relabel the work later to gain a larger limit.
  Use the stricter rule or split when the category is mixed or unclear.
- Path and count estimates forecast the work. They are not a fixed list and do
  not authorize extra behavior. A different path or count does not by itself
  require permission or another scope review. The work must still deliver the
  approved result and stay within the approved concerns, dependencies, output
  classes, and hard gates.
- After staging, record the actual paths and file count. Explain a meaningful
  difference from the forecast.
- Ordinary churn is information, not permission or a gate.
- Room under a limit does not allow work that the issue or user did not approve.
- Documentation size thresholds require focused review. They do not by
  themselves force a split or another scope review. Apply the rules in
  `.agents/review-policy.md` about approval for documentation changes and
  staged reviews.

### Review large plans before editing

- Before editing a plan that may cross a scope gate, ask a fresh read-only agent
  to run `$implementation-scope-review`. The user must first authorize
  subagents for the active session.
- Give the scope reviewer the same raw user decisions and resolved authority
  comparison used for issue design. An issue-design verdict is review evidence,
  not authority for a material criterion.
- The reviewer must return `single_pr`, `split_required`, or `revise`. For
  `single_pr` and `split_required`, map every acceptance criterion to a proposed
  pull request, identify dependencies, and recommend merge order.
- A `revise` verdict stops implementation. Correct the proposed design or
  return the source issue to `$issue-design-review`; do not split an
  unnecessary design merely to fit the gates.
- If the user has not allowed a planning agent, or none is available, stop
  oversized work and report the blocker. Do not waive the review.

### Do not add work that was not approved

A limit is a ceiling, not permission to add work. Stop before editing and ask
the user or owner to approve a new plan when the approved result cannot be
delivered safely without an unrequested refactor, incidental fix, new concern,
or new dependency.

- Omit an unrequested refactor when the approved behavior can be delivered
  safely without it. If the refactor is required, explain why and update the
  estimate before editing.
- Report an incidental finding without fixing it or filing an issue when it
  does not block correctness, safety, or validation. If it blocks the work,
  stop and ask the user or owner to approve a new plan.
- Add an abstraction, production API, configuration option, provider slot, or
  toggle only when it satisfies the **Production-Use Evidence** rule in
  [`.agents/review-policy.md`](.agents/review-policy.md). Apply the same rule to
  every compatibility path, default or fallback behavior, public overload, and
  extension point. Issue wording, tests, possible future uses, and established
  boundaries do not replace that evidence.
- Do not include unrelated formatting, renames, dead-code removal, dependency
  upgrades, cleanup, hardening tests, or documentation.
- Do not add or change a manifest, lock file, workflow, provider, account,
  network service, runtime component, build tool, or test dependency unless the
  approved issue includes the dependency and its consequences.

### Split work when a hard gate requires it

- Default to `split_required` when a plan crosses a hard gate for concerns,
  ordinary files, resulting code size, generated output, vendored output, or
  lock files.
- General approval of an issue or pull request is not an exception.
- Apply the **In-Place Guardrail Repair** rule in
  [`.agents/review-policy.md`](.agents/review-policy.md) only to a recorded
  repair that meets its failure or misbehavior conditions. Exclude that repair
  from the concern count, and apply its exception only to ordinary-file
  crossings caused by the named guardrail paths. Do not infer it from a
  reviewer finding or use it to waive another gate.
- For any other single-pull-request exception, record the exact gate, measured
  values, and the reason a split is unsafe or inseparable on the issue. Obtain
  explicit owner approval.
- A `split_required` verdict does not authorize a GitHub change. Present the
  numbered split to the owner first.
- Until the owner explicitly accepts that numbered split, report the finding
  and do not change GitHub.
- After the owner accepts the split, create and link only the child issues that
  map to the named parent acceptance criteria. Do this before implementation.
  Incidental findings, cleanup, speculative work, and new dependencies still
  need separate explicit user or owner instruction, or approval in the source
  issue.
- Keep the parent issue open. Give every pull request one coherent result and
  keep each slice independently safe and mergeable. Leave unfinished behavior
  disabled by default.
- During implementation, stop and rerun `$implementation-scope-review` if the
  diff crosses a hard gate; adds a concern, output class, or dependency;
  requires a custom protocol client; or materially exceeds the forecast because
  of an added mechanism or hardening.
- Split the work unless the applicable exception is fully recorded and the
  owner approves it as required.
- Do not silently expand or recategorize a pull request.
- If only a documentation threshold is crossed, record the measures and arrange
  focused staged review. Do not rerun the scope review for documentation size
  alone.

### Review the complete implementation

- Before opening or finalizing a nontrivial implementation pull request, stage
  the complete intended diff and ask a fresh read-only agent to run
  `$second-agent-review`.
- Decide each finding. Fix accepted findings narrowly. Record why any finding
  is rejected or deferred.
- Rerun the relevant checks and summarize the review in the pull request.
- Treat work as nontrivial when it changes runtime behavior, a public contract,
  scoring or data transformation, provider, privacy or security boundaries,
  deployment or CI behavior, migrations, or two or more files with coupled
  behavior.
- A tiny mechanical edit or wording-only documentation change may skip staged
  review only when the documentation authority and size rules do not require
  it.
  The pull request must say why no review was required.

### Review sensitive information before publication

Before an agent-authored push:

- Ask a fresh read-only agent to run `$sensitive-information-review`.
- Give it the exact source ref, remote destination, complete refspecs, and push
  options.
- Review every commit message and every introduced or changed blob version in
  the outgoing range. Include intermediate versions that are absent from the
  final diff.
- Inspect the payload referenced by every Git LFS pointer.
- Do not substitute a net diff. Do not expand the review to unrelated local
  secrets or uncommitted work.

Before creating a nontrivial pull request or publishing a relevant
agent-authored change to an existing pull request:

- Ask a fresh read-only agent to run `$sensitive-information-review` on the
  exact unpublished title, body, comment or reply, and attachment bytes.
  Include the destination repository and pull request.
- Settled Git and pull-request text may be reviewed together before their
  publication steps.
- Before creating the pull request, prove complete merge-base-to-head coverage
  with recorded full object IDs and reachability. Inspect any commit or blob
  that is not covered by a matching `clear` pre-push review.
- For an existing pull request, read live text and discussion only as context.
  Review only the new outgoing material.
- Review every attachment, including a PDF or other document, before upload.
  If later text refers to a URL created by the hosting service, review that
  outgoing reference with the later text.

Immediately before each publication step:

- Resolve the refs, refspecs, push options, destination, and exact unpublished
  bytes again.
- If an unpublished reviewed input changed, run a new review. Publishing the
  exact reviewed input does not invalidate its verdict.
- Review each later pull-request change separately and only for its new
  outgoing content.
- Do not run this mandatory review after publication or only for final handoff.
  At handoff, verify refs, checks, and pull-request state without changing them,
  and report the recorded pre-publication verdicts.

Sensitive-review verdicts:

- `clear` allows the reviewed publication step.
- `review_required` remains unresolved until the owner decides or complete
  review coverage is restored. Do not report it as passed.
- `block` stops the push or pull-request publication.
- Report a candidate only by an opaque review-local ID and sanitized location.
  Do not reproduce or fingerprint the value.
- Missing document tools, encryption, malformed or unsupported content, unsafe
  extraction, or exceeded inspection limits prevent a `clear` verdict.
- The reviewer does not execute active content or fix findings.
- A pre-commit review is optional and happens only when the user asks. It does
  not replace the required pre-push or pull-request review.
- If a credible sensitive value is found after publication, stop publishing
  further material and report the incident steps that the owner already
  specified. Do not describe that retrospective check as a passed gate.

## Expected Tools

As implementation continues, the expected stack remains:

- Backend: Java, Spring Boot, Postgres, Flyway or Liquibase.
- Installed client: a later focused Expo iOS/Android companion that keeps the
  web app first-class. Do not scaffold it until the web, feed, and calendar
  flow is complete and testers show recurring demand.
- Local infrastructure: Docker Compose for Postgres and integration dependencies.

Do not add an installed client or local infrastructure
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
