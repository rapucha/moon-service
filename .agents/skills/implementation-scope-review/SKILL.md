---
name: implementation-scope-review
description: Run an independent read-only pre-implementation scope review and return a single-PR or split-required verdict. Use when an issue, plan, or requested change may span multiple independently reviewable concerns, subsystems, providers, deployment layers, UI and backend work, CI, privacy policy, or a large expected diff; when project instructions define PR-size gates; or when Codex needs to map acceptance criteria into a safe ordered PR series before editing files.
---

# Implementation Scope Review

## Purpose

Review planned work before implementation so oversized or incoherent changes
are split before code accumulates. Operate read-only. The primary agent owns
the plan, issue updates, implementation, and final judgment.

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
- Project authority such as `AGENTS.md`, contribution rules, and PR template.
- Only the product, architecture, API, privacy, or deployment documents needed
  to understand the work.
- Any proposed file list, subsystem estimate, rollout constraint, or known
  dependency.
- The change class selected from the accepted issue, plus any generated,
  vendored, or lock-file paths and reproduction information.
- Existing parent or child issues that may already own part of the scope.
- The issue-design verdict or summary when project policy required one.

## Review Method

1. Restate the single intended user-visible or operational outcome and the
   accepted issue authority.
2. Determine the repository-defined change class from the accepted issue, not
   from the allowance the proposed diff needs. Use the stricter applicable gate
   for ambiguous or mixed work.
3. Enumerate independently reviewable concerns. Treat code, tests, and
   documentation supporting one behavior as one concern; do not merge distinct
   backend, frontend, deployment, CI, provider, privacy, or rollout decisions
   merely because one issue mentions them.
4. Estimate affected ordinary files and added-plus-deleted lines. Count
   generated, vendored, and lock changes separately using every repository
   dimension, including resulting bytes where required. Treat mixed
   authored/generated and agent/LLM-authored files as ordinary.
5. Test scope authority independently of numeric headroom. Identify unrequested
   refactors, incidental fixes, speculative abstractions or extensibility,
   opportunistic cleanup, unrelated tests/docs, and unapproved dependencies.
   Any required addition must stop for replan authority before editing.
6. Apply the repository's explicit scope gates. If none exist, use coherence,
   reviewability, independent safety, and rollback boundaries as heuristics and
   make the lack of numeric gates visible.
7. Map every acceptance criterion to one proposed PR. Identify dependencies and
   merge order.
8. Check that each slice is independently safe and mergeable. Keep unfinished
   capability disabled by default when partial rollout could expose it.
9. Return exactly one verdict: `single_pr` or `split_required`.

Default to `split_required` when any repository gate is crossed. Recommend a
single-PR exception only for a concrete inseparability or safety rationale.
Require the exact exceeded gate, measured values, rationale, and explicit owner
approval to be recorded through the project's normal workflow.

## Output Contract

Lead with this structure:

```text
Verdict: single_pr | split_required

Gate assessment:
- Change class: <repository-defined class and why>
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

Acceptance ownership:
- <criterion> -> <PR>

Proposed PR sequence:
1. <coherent outcome; dependencies; disabled-by-default boundary>

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
the repository change classification, ordinary and output gates, and semantic
stop/replan controls. Map every acceptance criterion to a proposed PR and
return single_pr or split_required. Make every proposed slice independently
safe and mergeable, identify dependencies and merge order, and state material
unknowns. Numeric headroom is not scope authority. Do not assume the primary
agent's preferred plan is correct.
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
