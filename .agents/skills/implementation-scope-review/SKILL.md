---
name: implementation-scope-review
description: Run an independent read-only pre-implementation scope review and return a single-PR or split-required verdict. Use when an issue, plan, or requested change may span multiple independently reviewable concerns, subsystems, providers, deployment layers, UI and backend work, CI, privacy policy, or a large expected diff; when project instructions define PR-size gates; or when Codex needs to map acceptance criteria into a safe ordered PR series before editing files.
---

# Implementation Scope Review

## Purpose

Review planned work before implementation so oversized or incoherent changes
are split before code accumulates. Prefer independently useful behavioral or
operational slices over file-, layer-, or line-count packaging. Numeric gates
constrain a plan; they do not define a good split. Operate read-only. The
primary agent owns the plan, issue updates, implementation, and final judgment.

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
- Ask for missing information only when it would materially change the verdict;
  otherwise state a bounded assumption.

## Inputs

Give the reviewer raw task context without the primary agent's preferred
answer:

- User request and source issue, including acceptance criteria.
- Evidence or owner decisions that make robustness and hardening criteria part
  of the required current outcome rather than optional follow-up work.
- Project authority such as `AGENTS.md`, contribution rules, and PR template.
- Only the product, architecture, API, privacy, or deployment documents needed
  to understand the work.
- Any proposed file list, subsystem estimate, rollout constraint, or known
  dependency.
- The change category selected from the accepted issue, plus any generated,
  vendored, or lock-file paths and reproduction information.
- Existing parent or child issues that may already own part of the scope.
- The issue-design verdict or summary when project policy required one.

## Review Method

1. Restate the single intended user-visible or operational outcome and the
   accepted issue authority.
2. Separate the minimum end-to-end capability from optional robustness or
   hardening. Trace deduplication, pagination, recovery, reconciliation,
   supersession, generalized extensibility, and hypothetical scale handling to
   accepted authority or a concrete current correctness/safety need.
3. Enumerate candidate behavioral or operational slices before considering file
   boundaries, architecture layers, or numeric fit. For every slice, state its
   observable outcome and apply the counterfactual: if no later slice ever
   lands, does this PR remain useful, operable, and independently verifiable?
   Reject an inert utility, foundation, migration, or wiring-only slice chosen
   for packaging convenience. Allow a disabled foundation only when a concrete
   safety, compatibility, or dependency ordering requires it; record that
   rationale and keep activation disabled.
4. Determine the repository-defined change category from the accepted issue, not
   from the allowance the proposed diff needs. Use the stricter applicable gate
   for ambiguous or mixed work.
5. Enumerate independently reviewable concerns. Treat code, tests, and
   documentation supporting one behavior as one concern; do not merge distinct
   backend, frontend, deployment, CI, provider, privacy, or rollout decisions
   merely because one issue mentions them.
6. Estimate affected ordinary files and added-plus-deleted lines. Count
   generated, vendored, and lock changes separately using every repository
   dimension, including resulting bytes where required. Treat mixed
   authored/generated and agent/LLM-authored files as ordinary.
7. Test scope authority independently of numeric headroom. Identify unrequested
   refactors, incidental fixes, speculative abstractions or extensibility,
   opportunistic cleanup, unrelated tests/docs, and unapproved dependencies.
   Any required addition must stop for replan authority before editing.
8. Apply the repository's explicit scope gates. Never remove tests,
   documentation, explanations, or safety checks needed to make a slice
   reviewable and operable, and never separate implementation from activation
   merely to fit a gate. If no behavioral split fits, request a scope change or
   a recorded exception instead of manufacturing an inert PR. If no explicit
   gates exist, use coherence,
   reviewability, independent safety, and rollback boundaries as heuristics and
   make the lack of numeric gates visible.
9. Map every acceptance criterion to one proposed PR. Identify dependencies and
   merge order.
10. Check that each slice is independently safe and mergeable. Keep unfinished
   capability disabled by default when partial rollout could expose it.
11. Return exactly one verdict: `single_pr` or `split_required`.

Default to `split_required` when any repository gate is crossed. Recommend a
single-PR exception only for a concrete inseparability or safety rationale.
Require the exact exceeded gate, measured values, rationale, and explicit owner
approval to be recorded through the project's normal workflow.

Do not recommend a mechanical split only because it satisfies a numeric gate.
When the smallest useful end-to-end behavior still exceeds a gate, report that
fact and require scope reduction, a coherent behavioral split, or the recorded
exception process.

## Output Contract

Lead with this structure:

```text
Verdict: single_pr | split_required

Gate assessment:
- Change category: <repository-defined category and why>
- Applicable gates: <concerns/files/lines and output budgets>
- Concerns/subsystems: <count and names>
- Ordinary files: <estimate>
- Ordinary lines: <estimate or bounded range>
- Generated output: <files/lines/bytes/reproduction or none>
- Vendored output: <files/bytes/authority/provenance or none>
- Lock files: <files/lines/bytes/reproduction or none>
- Triggered gates: <list or none>

Scope-authority assessment:
- <unrequested work, abstraction/YAGNI, incidental finding, dependency, or none>
- Follow-up mutation authority: <explicit user/source authority or owner-accepted enumerated split, including exact authorized issues>

Behavioral decomposition:
- Minimum end-to-end capability: <smallest useful observable outcome>
- Optional hardening: <excluded, justified in current scope, or separately authorized>
- Counterfactual value: <why every proposed slice remains useful if later slices never land>

Acceptance ownership:
- <criterion> -> <PR>

Proposed PR sequence:
1. <observable coherent outcome; counterfactual value; dependencies; disabled-by-default boundary or justified foundation exception>

Risks and unknowns:
- <material ambiguity, unsafe split, missing authority, or validation need>

Primary-agent next action:
- <proceed with one PR, or present an enumerated split plan for owner acceptance>
```

For `single_pr`, explain why the change is one coherent review unit. For
`split_required`, propose the smallest practical ordered series rather than a
broad rewrite of the parent issue.

## Reviewer Prompt

Use a prompt like:

```text
Use a read-only planning-review stance. Review the issue and relevant project
authority before implementation. Do not edit files or external state. Apply
the repository change categorization, ordinary and output gates, and semantic
stop/replan controls. Identify the minimum end-to-end capability before file or
layer boundaries, separate optional hardening, and apply the counterfactual
value test to every proposed slice. Map every acceptance criterion to a
proposed PR and return single_pr or split_required. Make every proposed slice
independently useful, safe, and mergeable; allow an inert disabled foundation
only for a documented safety or ordering dependency. Identify dependencies,
merge order, and material unknowns. Numeric headroom is neither scope authority
nor a reason for mechanical packaging. Do not assume the primary agent's
preferred plan is correct.
```

## Primary-Agent Follow-Up

- Record the verdict on the source issue or active plan when project workflow
  requires it.
- If `split_required`, present an enumerated child-issue plan for owner
  acceptance. Create and link only the children explicitly authorized by the
  accepted plan, then keep the parent open.
- If `single_pr`, record the coherent outcome and gate estimate before editing.
- Re-run scope review if the actual diff crosses a gate or gains a new concern,
  output category, or dependency.
- Do not treat planning approval as code-review approval.
