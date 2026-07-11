---
name: issue-design-review
description: Run an independent read-only design review of a draft GitHub issue before an agent creates it or treats it as implementation authority. Use when Codex drafts a nontrivial implementation, technical-debt, follow-up, decision, dependency, privacy, or operational issue; when a draft may hide assumptions or span multiple outcomes; or when project policy requires validation of issue premise, alternatives, owner decisions, scope, and acceptance criteria.
---

# Issue Design Review

## Purpose

Review an issue draft before it becomes external project state or implementation
authority. Challenge whether the issue is the right work, not merely how its
accepted work should be packaged into pull requests.

This is an issue-design review. It does not replace
`$implementation-scope-review` before implementation or
`$second-agent-review` after changes are staged.

## Preconditions

- Delegate to a fresh read-only subagent that did not draft the issue.
- Confirm delegation is allowed and any project-required user authorization for
  subagents exists.
- Keep the review read-only: do not create or edit issues, files, branches, pull
  requests, or other external state.
- Review the target project's authority before applying generic heuristics.
- If project policy requires this review and a fresh agent is unavailable,
  pause and report the blocker rather than waiving the gate.
- Ask the user only when a missing answer materially changes the proposed work;
  otherwise state a bounded assumption.

## Inputs

Give the reviewer raw source material rather than the drafting agent's preferred
answer:

- The proposed issue title and complete draft body.
- The user's request, evidence, failure, or feedback that motivated the draft.
- Project authority such as `AGENTS.md`, contribution rules, and relevant issue
  templates.
- Only the product, architecture, privacy, deployment, or API documents needed
  to test the premise.
- Existing issues or pull requests that may duplicate, supersede, or own part
  of the work.
- The proposed repository change class; estimated ordinary, generated, vendored,
  and lock-file output; and authority for any follow-up issue or dependency.
- Known dependencies, rollout constraints, assumptions, and owner decisions.

## Review Method

1. Restate the concrete user-visible or operational problem and the evidence
   that it exists.
2. Check whether an existing issue, pull request, or documented decision already
   owns the work.
3. Challenge the proposed solution and identify materially simpler alternatives
   or cases where no action is warranted. Reject speculative abstractions,
   extension points, cleanup, or refactors that the stated outcome does not
   require.
4. Surface hidden decisions and dependencies, including new runtime components,
   providers, accounts, stored data, privacy obligations, network exposure,
   deployment or rollback work, CI, and ongoing operations.
5. Separate owner decisions from implementation details. Do not mark a draft
   ready while a material product, architecture, privacy, cost, or operational
   choice is implicit or unauthorized.
6. Classify the proposed work from its outcome and apply every repository gate:
   concerns, ordinary files/lines, and separate generated, vendored, and
   lock-file budgets. Do not choose a more generous class to fit an estimate.
7. Identify incidental findings, opportunistic cleanup, unrelated tests/docs,
   and dependencies not authorized by the user's request or existing source
   issue. Remove them or return `revise`; numeric headroom is not authority.
8. Split distinct outcomes, decision work, or independently deliverable
   concerns rather than hiding them under one issue.
9. Test acceptance criteria for observable outcomes, completeness, feasibility,
   and solution bias. Identify prerequisites, dependency order, rollback
   boundaries, and disabled-by-default needs.
10. Return exactly one verdict: `ready`, `revise`, or `split_required`.

## Verdict Rules

- `ready`: the problem is supported, the scope is coherent, material decisions
  are explicit and authorized, and acceptance criteria are testable.
- `revise`: one coherent issue remains appropriate, but its premise, choices,
  dependencies, boundaries, scope authority, or acceptance criteria need
  correction before it is created or acted upon.
- `split_required`: the draft combines independently reviewable outcomes,
  crosses a project scope gate, or mixes a prerequisite decision with
  implementation that should not begin before that decision.

Default to `revise` when a material owner decision is missing. Default to
`split_required` when a repository gate is crossed unless the project records
an approved exception through its normal workflow.

## Output Contract

Lead with this structure:

```text
Verdict: ready | revise | split_required

Problem and evidence:
- Intended outcome: <single outcome>
- Evidence: <why this issue is needed>

Ownership and duplication:
- Existing authority: <issue, PR, decision, or none>

Decisions and hidden dependencies:
- <decision/dependency; owner approval state>

Scope assessment:
- Change class: <repository-defined class and why>
- Concerns/subsystems: <count and names>
- Expected files/lines: <estimate or bounded range>
- Generated/vendored/lock output: <budget assessment or none>
- Scope-authority findings: <unrequested work, dependency, follow-up authority, or none>
- Triggered gates: <list or none>

Acceptance-quality findings:
- <missing, untestable, solution-biased, or complete criterion>

Proposed issue set:
1. <issue outcome; dependencies and ordering>

Questions for the owner:
- <only questions that materially block a ready draft>

Primary-agent next action:
- <create, revise and re-review, or present a split draft for approval>
```

For `ready`, explain why one issue is coherent. For `revise`, provide the
smallest corrections needed. For `split_required`, map every proposed
acceptance criterion to the smallest practical ordered issue set.

## Reviewer Prompt

Use a prompt like:

```text
Use a read-only issue-design-review stance. Review the draft issue and relevant
project authority before it is created or treated as implementation authority.
Do not edit files or external state. Challenge the premise, alternatives,
hidden dependencies, owner decisions, scope authority, YAGNI, and acceptance
criteria. Apply the repository change class and all ordinary/output gates and
return exactly ready, revise, or split_required. Do not assume the drafting
agent's proposed solution is correct.
```

## Primary-Agent Follow-Up

- For `ready`, create the issue only when authorized and record the verdict or
  a concise review summary in the issue.
- For `revise`, correct the draft and re-run review when the changes are
  material; do not publish the rejected version as implementation authority.
- For `split_required`, present the ordered issue set for owner acceptance.
  Create only children explicitly authorized by that accepted plan, keep the
  parent open, and keep prerequisite decisions ahead of implementation.
- Preserve the reviewer output as evidence, but do not treat it as owner
  approval for a material decision.
- Reviewer suggestions do not authorize incidental follow-up issues. Without
  explicit user/owner instruction, explicit source-issue authority, or an
  owner-accepted enumerated split plan, report the observation without mutating
  external state.
- If reviewing an existing issue, update or comment on it only when the user
  authorized that external mutation.
