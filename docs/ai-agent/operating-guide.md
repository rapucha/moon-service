# AI Agent Operating Guide

Purpose
- Define the default workflow for AI-assisted changes in Moon Service.
- Preserve a small, deliberate MVP trajectory while implementation expands.
- Make validation evidence and handoff state explicit.

Standard Workflow
1. Identify the task type.
   - Documentation or planning.
   - Backend implementation.
   - API contract change.
   - Scoring, ephemeris, geocoding, or weather logic.
   - Refactor.
   - Test authoring.
   - Build or project-model fix.

2. Load context.
   - Read `AGENTS.md`.
   - Read `docs/README.md` for human-owned documentation locations.
   - Read the matching context pack in this folder.
   - Read directly affected source and docs.

3. Assess parallelism.
   - State whether subagents would materially help.
   - Do not spawn subagents unless the user explicitly authorizes delegation.
   - Good candidates: independent research, disjoint implementation slices,
     review passes, broad test-gap analysis.
   - Poor candidates: small edits, tightly coupled refactors, one-file fixes.

4. Plan substantial work.
   - State goal, scope, likely files, risks, validation commands, and rollback
     path when relevant.
   - For small tasks, a short inline plan is enough.

5. Implement narrowly.
   - Prefer existing project patterns.
   - Keep changes scoped to the request.
   - Do not introduce persistence, accounts, Android, deployment, or live
     providers unless explicitly requested and documented.
   - For behavior changes, update tests or explain why tests are not useful yet.

6. Validate.
   - Run the smallest meaningful command first.
   - Broaden validation when touching shared contracts, scoring/window logic, or
     module structure.
   - Run `git diff --check` before finishing file changes.

7. Report outcome.
   - Summarize what changed and why.
   - List validation commands and outcomes.
   - Call out skipped validation, untracked files, generated files, or residual
     risks.

Communication Standard
- State technical judgment directly.
- Pair agreement with evidence or reasoning.
- Make disagreement specific and actionable.
- Separate the claim, the caveat, and the validation path.
- Avoid generic appeasing phrases such as "your instinct is right", "you are
  absolutely right", or "great idea" without analysis.

When To Update Docs
- Public API behavior changes.
- Module boundaries change.
- Build/test commands change.
- Provider, privacy, caching, or storage behavior changes.
- A repeated workflow needs a runbook.

When To Use Task State
- The task spans multiple sessions.
- There are non-obvious decisions or failed paths to preserve.
- A handoff note would prevent rediscovery.
