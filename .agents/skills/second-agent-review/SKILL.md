---
name: second-agent-review
description: Run an independent read-only second-agent review after implementation work. Use when Codex should ask a separate agent to inspect staged or branch changes for bugs, contract drift, missing tests, privacy/provider risks, UI regressions, documentation mismatches, or other review findings before finalizing a task or pull request. Especially useful for nontrivial code changes, public API changes, scoring/modeling changes, provider integrations, UI/layout work, refactors, and PR-ready issue branches.
---

# Second Agent Review

## Overview

Use this skill to add an independent review pass after a primary implementation
agent has made changes. The reviewer should operate in a read-only,
code-review stance and report findings; the primary agent remains responsible
for triage, fixes, validation, and the final answer.

This skill is intentionally project-agnostic. Load the target project's own
authority files and diff instead of encoding project-specific rules here.

This is a post-implementation review. It does not replace
`$issue-design-review` before an agent-created issue becomes implementation
authority or `$implementation-scope-review` before coding an issue or plan that
may cross the target project's PR-size gates.

## Preconditions

Use a second-agent review only when delegation is allowed in the current
conversation and tool environment. If project instructions require explicit
user authorization before spawning subagents, get that authorization first.

Before requesting the review, the primary agent should:

- Complete the intended implementation pass.
- Run the smallest meaningful validation commands, unless blocked.
- Check the worktree state and identify the intended diff to review.
- Keep unrelated user or generated changes separate when possible.
- Read `.agents/review-policy.md` before applying project gates, triggers,
  measures, or planning estimates.

## When To Use

Use this review for changes with meaningful risk or enough surface area that an
independent pass can add value:

- Public API, serialization, route, or error-contract changes.
- Scoring, ranking, model, parser, scheduling, or data-transformation logic.
- External provider, network, cache, quota, observability, auth, or privacy boundaries.
- UI behavior that needs responsive, accessibility, state, or layout judgment.
- Refactors, renames, ownership-boundary moves, and module restructuring.
- Policy, workflow, scope-control, or dependency changes where the completed
  diff must remain aligned with accepted authority.
- PR-ready work where missed tests or docs could delay review.
- Any change the primary agent found subtle, surprising, or hard to validate.

Skip it for tiny mechanical edits, trivial docs-only wording changes, or
single-line fixes where the review overhead is higher than the risk.

## Context To Pass

Give the reviewer enough raw context to reconstruct the review independently,
but avoid leaking the primary agent's conclusions or suspected bugs unless the
review explicitly depends on them.

Prefer these inputs:

- Project authority files, for example `AGENTS.md`, `README.md`, contribution docs, or policy docs.
- Relevant design/API/product docs, not every document in the repository.
- The staged diff, branch diff, or exact file list under review.
- The accepted change category, scope review, explicit source-issue authority, and
  any approved exception or owner-accepted enumerated split plan.
- Expected paths, other paths that might be needed and why, the expected
  file-count range, actual paths and count, and any meaningful difference.
  Include informational churn; code-line base counts, results, and deltas;
  documentation authority classes, sizes, and triggers; and generated,
  vendored, and lock-file counts, sizes, reproduction commands, and validation
  evidence when present.
- The validation commands already run and their outcomes.
- Any intentionally untracked/generated files that should be ignored.

For Git worktrees, prefer reviewing `git diff --cached` when the task policy
stages intended changes before finalization. Otherwise use `git diff` or a
branch comparison that matches the actual review scope.

## Reviewer Instructions

Ask the second agent to review, not edit. Use a prompt like this:

```text
Use a read-only code-review stance. Review the provided diff against the
project instructions, `.agents/review-policy.md`, and relevant docs. Read the
policy before applying its gates or triggers. Do not modify files.

Check scope before implementation quality:

1. Compare the staged diff with the accepted outcome, change category, acceptance
   criteria, and approved exceptions. Flag unaccepted concerns even when the
   diff remains below its hard gates. Do not flag an extra, missing, or replaced
   planned path by itself. Check whether the difference changes behavior,
   concerns, dependencies, output classes, or a hard gate.
2. Verify ordinary file counts, informational churn, code-line base and result
   counts, oversized-file deltas and exceptions, and generated/vendored/lock
   measurements against project policy. Record meaningful differences from the
   planned file range without treating unused range as scope. Treat mixed or
   agent/LLM-authored files as ordinary.
3. For each changed document, verify its authority class, resulting nonblank
   lines, changed nonblank lines, and review trigger. When focused review is
   required, check for removed constraints, contradictions, new authority,
   repeated rules, mismatch with code, and unclear structure. Documentation
   size alone does not require a split.
4. Look for unrequested refactors, incidental fixes, opportunistic cleanup,
   speculative extensibility, unrelated tests/docs, and abstractions without a
   current accepted production use or established boundary.
5. Flag manifest, lock, workflow, provider, account, network, runtime, build, or
   test dependencies that lack explicit source authority.
6. Then prioritize concrete bugs, behavior regressions, public contract drift,
   missing or weak tests, privacy/security/provider risks, UI/layout risks,
   documentation mismatches, and validation gaps.
7. Review changed agent-authored prose for plain language. Flag wording only
   when a simpler version keeps the same meaning, and suggest that version.
   Keep exact contract language and needed technical terms. Code comments
   should explain why rather than restate obvious code.

Do not turn the plain-language rule into personal style preferences, a word
limit, or a readability score.

For each finding, include severity, file and line reference when possible,
why it matters, and the smallest practical fix. If there are no findings,
say that clearly and mention any residual test or validation risk.
```

The reviewer output should lead with findings ordered by severity. It should
not include broad implementation rewrites unless a rewrite is the smallest
practical fix for a real issue.

Use short, direct sentences in the review itself. Avoid formal filler and
decorative metaphors.

## Primary Agent Follow-Up

After receiving the review:

- Triage every finding as accepted, rejected, or deferred.
- Fix accepted findings narrowly.
- Explain rejected findings with a concrete reason, not a preference.
- Convert deferred findings into project follow-ups only when the project's workflow calls for that.
- Do not create a follow-up from a reviewer suggestion unless explicit
  user/owner instruction, explicit source-issue authority, or an owner-accepted
  enumerated split plan permits it; otherwise report the observation without
  external mutation.
- Rerun the relevant validation after any fixes.
- Mention the second-agent review outcome in the final response or PR summary when useful.

Do not blindly apply the reviewer output. The primary agent owns the final
technical judgment and must preserve project instructions, user intent, and
the current worktree state.
