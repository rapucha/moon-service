---
name: issue-design-review
description: Run an independent read-only design review of a draft GitHub issue before an agent creates it or treats it as implementation authority. Use when Codex drafts a nontrivial implementation, technical-debt, follow-up, decision, dependency, privacy, or operational issue; when a draft may hide assumptions or span multiple outcomes; or when project policy requires validation of issue premise, alternatives, owner decisions, scope, and acceptance criteria.
---

# Issue Design Review

## Purpose

Review an issue draft before it becomes external project state or implementation
authority. Challenge whether the issue is the right work, not merely how its
accepted work should be packaged into pull requests. Prevent an agent-authored
draft from manufacturing scope authority by converting optional robustness into
required acceptance criteria without user intent, evidence, correctness, or
safety need.

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
- The trace from each material acceptance criterion to that request, evidence,
  an established contract, or an explicit owner decision.
- Project authority such as `AGENTS.md`, contribution rules, and relevant issue
  templates.
- Only the product, architecture, privacy, deployment, or API documents needed
  to test the premise.
- Existing issues or pull requests that may duplicate, supersede, or own part
  of the work.
- The proposed repository change category; expected ordinary files and
  informational churn; expected code-file sizes; base counts and deltas for
  existing oversized files; documentation measures; generated, vendored, and
  lock-file output; and authority for any follow-up issue or dependency.
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
4. Classify proposed behavior as the minimum end-to-end capability, behavior
   required for current correctness or safety, or optional hardening. Require a
   concrete authority or evidence trace for each material criterion; the draft's
   own wording is not authority. Treat deduplication, pagination, recovery,
   reconciliation, supersession, generalized extensibility, and hypothetical
   scale handling as optional unless the inputs demonstrate otherwise.
5. Prefer an issue whose first deliverable is the smallest observable
   user-visible or operational outcome. Defer optional hardening rather than
   embedding it in the core issue. Do not create or presume a follow-up issue
   unless the user, source authority, or an owner-accepted split authorizes it.
6. Surface hidden decisions and dependencies, including new runtime components,
   providers, accounts, stored data, privacy obligations, network exposure,
   deployment or rollback work, CI, and ongoing operations.
7. Separate owner decisions from implementation details. Do not mark a draft
   ready while a material product, architecture, privacy, cost, or operational
   choice is implicit or unauthorized.
8. Categorize the proposed work from its outcome and apply every repository
   gate: concerns, ordinary files, resulting code-file sizes, and separate
   generated, vendored, and lock-file budgets. Record ordinary line churn as
   information. Check documentation authority and size rules without treating
   their review thresholds as scope gates. Do not choose a more generous
   category to fit an estimate.
9. Identify incidental findings, opportunistic cleanup, unrelated tests/docs,
   and dependencies not authorized by the user's request or existing source
   issue. Remove them or return `revise`; room under a limit is not authority.
10. Split distinct outcomes, decision work, or independently deliverable
   concerns rather than hiding them under one issue.
11. Test acceptance criteria for observable outcomes, completeness,
    feasibility, solution bias, and authority traceability. Identify
    prerequisites, dependency order, rollback boundaries, and
    disabled-by-default needs. For agent-authored issue text, flag wording that
    can be simpler without losing meaning and propose a direct replacement.
12. Return exactly one verdict: `ready`, `revise`, or `split_required`.

## Verdict Rules

- `ready`: the problem is supported, the scope is coherent, material decisions
  and criteria are explicit and authorized, optional hardening is justified or
  excluded, and acceptance criteria are testable.
- `revise`: one coherent issue remains appropriate, but its premise, choices,
  dependencies, boundaries, scope authority, or acceptance criteria need
  correction before it is created or acted upon.
- `split_required`: the draft combines independently reviewable outcomes,
  crosses a hard project scope gate, or mixes a prerequisite decision with
  implementation that should not begin before that decision. A documentation
  review threshold alone does not require a split.

Default to `revise` when a material owner decision is missing. Default to
`split_required` when a hard repository gate is crossed unless the project
records an approved exception through its normal workflow.

## Output Contract

Lead with this structure:

```text
Verdict: ready | revise | split_required

Problem and evidence:
- Intended outcome: <single outcome>
- Evidence: <why this issue is needed>
- Minimum end-to-end capability: <smallest observable useful result>

Ownership and duplication:
- Existing authority: <issue, PR, decision, or none>

Decisions and hidden dependencies:
- <decision/dependency; owner approval state>

Acceptance authority:
- <material criterion> -> <user intent, evidence, correctness/safety need, owner decision, or unsupported>
- Optional hardening: <excluded, justified in current scope, or separately authorized>

Scope assessment:
- Change category: <repository-defined category and why>
- Concerns/subsystems: <count and names>
- Ordinary files/churn: <file estimate and informational added-plus-deleted lines>
- Code sizes: <paths, types, base counts, expected results, deltas, and limits>
- Documentation review: <authority classes, resulting/changed nonblank lines, and triggers>
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

Use short, direct sentences. Keep exact technical terms when they are needed.
Avoid formal filler and decorative metaphors.

## Reviewer Prompt

Use a prompt like:

```text
Use a read-only issue-design-review stance. Review the draft issue and relevant
project authority before it is created or treated as implementation authority.
Do not edit files or external state. Challenge the premise, alternatives,
hidden dependencies, owner decisions, scope authority, YAGNI, and acceptance
criteria. Trace every material criterion to user intent, evidence, a current
correctness/safety need, or an explicit decision. Identify the minimum
end-to-end capability and separate unsupported optional hardening. Apply the
repository change category, hard scope gates, documentation review rules, and
output budgets. Treat ordinary churn as information. Return exactly ready,
revise, or split_required. Do not assume the drafting agent's proposed solution
or its own acceptance criteria are authoritative. Use plain, direct language
and suggest simpler wording when it preserves the meaning.
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
