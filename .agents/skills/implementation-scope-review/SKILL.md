---
name: implementation-scope-review
description: Run an independent read-only pre-implementation scope review and return a single-PR, split-required, or revise verdict. Use when an issue, plan, or requested change may span multiple independently reviewable concerns, subsystems, providers, deployment layers, UI and backend work, CI, privacy policy, or a large expected diff; when project instructions define PR-size gates; or when Codex needs to map acceptance criteria into a safe ordered PR series before editing files.
---

# Implementation Scope Review

## Purpose

Review planned work before implementation so oversized or incoherent changes
are split before code accumulates. Prefer independently useful behavioral or
operational slices over file-, layer-, or line-count packaging. Numeric gates
constrain a plan; they do not define a good split. Operate read-only. The
primary agent owns the plan, issue updates, implementation, and final judgment.
Return an unnecessarily strict or materially expanded design for revision
instead of splitting it into several unnecessary pull requests.

This skill reviews scope, not completed code. After implementation, use
`$second-agent-review` when available for an independent staged-diff review.

This skill assumes the source issue is suitable implementation authority. When
an agent drafted a nontrivial issue, use `$issue-design-review` first to test
its premise, hidden decisions, alternatives, and issue boundaries. Issue-design
review does not replace this PR-packaging review.

## Preconditions

- The invoking primary agent must delegate the review to a fresh read-only
  subagent that did not draft the proposed plan. Self-review does not satisfy
  this skill.
- Confirm delegation is allowed and any project-required user authorization
  for subagents exists. If a fresh agent cannot be used, stop and report the
  blocker instead of returning a scope verdict.
- Do not edit files, create issues, change branches, or mutate external state.
- Review the target project instructions before applying generic heuristics.
- Read `.agents/review-policy.md` before applying project gates, triggers,
  measures, or planning estimates.
- Ask for missing information only when it would materially change the verdict;
  otherwise state a bounded assumption.

## Inputs

Give the reviewer raw task context without the primary agent's preferred
answer:

- User request and source issue, including acceptance criteria.
- The user's exact statements about the current threat model, risk tolerance,
  acceptable failure, and maintenance tradeoffs.
- Evidence or owner decisions that make robustness and hardening criteria part
  of the required current outcome rather than optional follow-up work.
- Project authority such as `AGENTS.md`, contribution rules, and PR template.
- Only the product, architecture, API, privacy, or deployment documents needed
  to understand the work.
- Expected paths; other paths that may be needed and why; an expected
  ordinary-file count range; a subsystem estimate; an informational
  ordinary-churn range; expected code-file sizes; base counts and deltas for
  existing oversized files; documentation measures; rollout constraints; and
  known dependencies.
- The change category selected from the accepted issue, plus any generated,
  vendored, or lock-file paths and reproduction information.
- Existing parent or child issues that may already own part of the scope.
- The issue-design verdict or summary when project policy required one.

## Review Method

1. Restate the single intended user-visible or operational outcome and the
   accepted issue authority.
2. Identify the smallest standard implementation that matches the current
   accepted threat model, then separate the minimum end-to-end capability from
   optional robustness or hardening. Compare any materially stricter
   alternative by current need, tradeoffs, and ongoing maintenance cost. Trace
   deduplication, pagination, recovery, reconciliation, supersession,
   generalized extensibility, and hypothetical scale or threat handling to
   accepted authority or a concrete current correctness/safety need. If the
   plan requires a custom protocol client, return `revise` unless the owner
   explicitly approved it after seeing the standard alternative.
3. List candidate behavioral or operational slices before considering files,
   architecture layers, or limits. State each slice's observable outcome. Ask
   whether it stays useful, operable, and verifiable if no later slice lands.
   Reject a utility, foundation, migration, or wiring-only slice chosen only to
   package the work. Allow a disabled foundation only when safety,
   compatibility, or dependency order requires it. Record that reason and keep
   activation disabled.
4. Determine the repository-defined change category from the accepted issue, not
   from the allowance the proposed diff needs. Use the stricter applicable gate
   for ambiguous or mixed work.
5. Enumerate independently reviewable concerns. Treat code, tests, and
   documentation supporting one behavior as one concern; do not merge distinct
   backend, frontend, deployment, CI, provider, privacy, or rollout decisions
   merely because one issue mentions them.
6. Record expected paths and other paths that may be needed, with the reason for
   each one. Estimate an ordinary-file count range and informational
   added-plus-deleted-line range. These forecast the implementation; they are
   not a fixed path list or permission to add work. List each changed code file
   with its type, base count, expected result, delta, and applicable limit.
   For an existing oversized file, state whether the expected delta is zero or
   an approved exception is required. Record each changed document's authority
   class, resulting nonblank lines, changed nonblank lines, and review trigger.
   Count generated, vendored, and lock changes separately using every
   repository dimension, including resulting bytes where required. Treat mixed
   authored/generated and agent/LLM-authored files as ordinary. Compare the
   plan with the issue-design forecast. Forecast growth alone is informational;
   return `revise` when substantial growth reveals an unreviewed mechanism,
   concern, dependency, output class, or hardening choice.
7. Check scope authority separately from the remaining room under a limit.
   Identify unrequested refactors, incidental fixes, speculative abstractions
   or extension points, opportunistic cleanup, unrelated tests/docs, and
   unapproved dependencies.
   Any required addition must stop for replan authority before editing.
8. Apply the repository's hard scope gates. Never remove tests, documentation,
   explanations, or safety checks needed to make a slice reviewable and
   operable, and never separate implementation from activation merely to fit a
   gate. Documentation review thresholds require review evidence, not a split
   or another scope review. If no behavioral split fits, request a scope change
   or a recorded exception instead of manufacturing an inert PR. If no explicit
   gates exist, use coherence, reviewability, independent safety, and rollback
   boundaries as heuristics and make the lack of hard gates visible.
9. Map every acceptance criterion to one proposed PR. Identify dependencies and
   merge order.
10. Check that each slice is independently safe and mergeable. Keep unfinished
   capability disabled by default when partial rollout could expose it.
11. Return exactly one verdict: `single_pr`, `split_required`, or `revise`.

Default to `split_required` when any hard repository gate is crossed.
Documentation review thresholds are not hard gates. Recommend a single-PR
exception only for a concrete inseparability or safety rationale. Require the
exact exceeded gate, measured values, rationale, and explicit owner approval to
be recorded through the project's normal workflow.

Return `revise` when the proposed design has not established proportionality,
requires an unapproved custom protocol client, converts a hypothetical threat
into current scope, or materially expands the reviewed forecast through an
unapproved mechanism or hardening choice. Send an issue premise or authority
problem back through `$issue-design-review`. Do not use `split_required` to
preserve an unnecessary design.

Do not recommend a mechanical split only because it satisfies a numeric gate.
When the smallest useful end-to-end behavior still exceeds a gate, report that
fact and require scope reduction, a coherent behavioral split, or the recorded
exception process.

## Output Contract

Lead with this structure:

```text
Verdict: single_pr | split_required | revise

Gate assessment:
- Change category: <repository-defined category and why>
- Applicable hard gates: <concerns, ordinary files, resulting code sizes, and output budgets>
- Concerns/subsystems: <count and names>
- Expected paths: <likely paths; other paths that may be needed and why>
- Ordinary files: <expected count range>
- Ordinary churn: <informational estimated range>
- Code sizes: <paths, types, base counts, expected results, deltas, and limits>
- Documentation review: <paths, authority classes, resulting/changed nonblank lines, and triggers>
- Generated output: <files/lines/bytes/reproduction or none>
- Vendored output: <files/bytes/authority/provenance or none>
- Lock files: <files/lines/bytes/reproduction or none>
- Hard-gate crossings: <list or none>
- Review triggers: <list or none>

Scope-authority assessment:
- <unrequested work, abstraction/YAGNI, incidental finding, dependency, or none>
- Follow-up mutation authority: <explicit user/source authority or owner-accepted enumerated split, including exact authorized issues>

Behavioral decomposition:
- Minimum end-to-end capability: <smallest useful observable outcome>
- Optional hardening: <excluded, justified in current scope, or separately authorized>
- Independent value: <why every proposed slice remains useful if later slices never land>

Acceptance ownership:
- <criterion> -> <PR>

Proposed PR sequence:
1. <observable outcome; value if later work stops; dependencies; safe disabled state if needed>

Risks and unknowns:
- <material ambiguity, unsafe split, missing authority, or validation need>

Primary-agent next action:
- <proceed with one PR, present an enumerated split plan, or revise and repeat issue design>
```

For `single_pr`, explain why the change is one coherent review unit. For
`split_required`, propose the smallest practical ordered series rather than a
broad rewrite of the parent issue. For `revise`, name the unsupported stricter
choice or scope expansion and the smallest correction needed before planning
resumes.

Use short, direct sentences. Keep exact technical terms when they are needed.
Avoid formal filler and decorative metaphors.

## Reviewer Prompt

Use a prompt like:

```text
Use a read-only planning-review stance. Review the issue and relevant project
authority before implementation. Read `.agents/review-policy.md` before
applying its gates or triggers. Do not edit files or external state. Apply the
repository change category, hard scope gates, documentation review rules,
output budgets, and semantic stop/replan controls. Treat ordinary churn as
information. Start with the smallest standard implementation that matches the
current accepted threat model. Present materially stricter alternatives with
their current need, tradeoffs, and maintenance cost. Return revise for an
unapproved stricter design, custom protocol client, or material forecast growth
caused by an unreviewed mechanism or hardening choice. Separate optional work.
For each slice, ask whether it remains useful if later slices never land. Allow
a disabled foundation only when safety or dependency order requires it. Map
every acceptance criterion to a proposed PR. Return single_pr, split_required,
or revise. Make each slice safe and mergeable. Name dependencies, merge order,
risks, and unknowns. Room under a limit is not scope authority or a reason for
a mechanical split. Do not assume the primary agent's plan is correct. Use
plain, direct language.
```

## Primary-Agent Follow-Up

- Record the verdict on the source issue or active plan when project workflow
  requires it.
- If `split_required`, present an enumerated child-issue plan for owner
  acceptance. Create and link only the children explicitly authorized by the
  accepted plan, then keep the parent open.
- If `revise`, stop implementation. Correct the plan or return the source issue
  to `$issue-design-review`, obtain any missing owner decision, and repeat this
  review before editing.
- If `single_pr`, record the coherent outcome and gate estimate before editing.
- If a documentation review threshold is expected, record the measures and
  focused-review requirement without splitting for size alone.
- Re-run scope review if the actual diff crosses a hard gate or gains a new
  concern, output category, or dependency.
- Do not rerun it for a path or count difference alone when the actual work
  keeps the accepted outcome, concerns, dependencies, output classes, and hard
  gates. Record the actual paths and count in the pull request. Explain any
  meaningful difference from the plan.
- Do not treat planning approval as code-review approval.
