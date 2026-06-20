# AI Agent Operating Guide Draft

Purpose
- Provide a consistent workflow for AI-assisted changes in Moon Service.
- Preserve the current product discipline while the repo moves from prototypes
  into a thin real backend.
- Make validation evidence and handoff state explicit.

Core Workflow
1. Identify the task type.
   - Documentation or planning
   - Backend spine implementation
   - API contract change
   - Scoring, ephemeris, geocoding, or weather logic
   - Refactor
   - Test authoring
   - Build or IDE/project-model fix

2. Load relevant context.
   - Read `AGENTS.md`.
   - Read the matching context pack in this draft.
   - Read directly affected docs and source files.
   - Prefer local context over assumptions.

3. Decide whether parallel work would help.
   - Say whether subagents would materially help.
   - Do not delegate unless the user explicitly authorizes it.
   - Good candidates: independent research, disjoint implementation slices,
     review passes, broad test-gap analysis.
   - Poor candidates: small edits, tightly coupled refactors, one-file fixes.

4. Plan before substantial changes.
   - State goal, scope, files likely to change, risks, validation commands, and
     rollback or revert path where relevant.
   - For small tasks, a short inline plan is enough.

5. Implement narrowly.
   - Prefer existing project patterns.
   - Keep changes scoped to the task.
   - Do not introduce accounts, persistence, Android, deployment, or live
     providers without an explicit next step.
   - For behavior changes, update tests or explain why tests are not useful yet.

6. Validate.
   - Run the smallest meaningful command first.
   - Broaden validation when touching shared behavior, contracts, or module
     structure.
   - Always run `git diff --check` before finishing changes.

7. Report outcome.
   - Summarize files changed and behavioral impact.
   - List validation commands and results.
   - Call out untracked files, skipped tests, or unresolved risks.

Recommended Final Response Shape
- What changed.
- Verification performed.
- Anything not done or intentionally deferred.
- Concrete next step, only when it follows directly from the work.

When to Update Docs
- Module boundaries changed.
- API contract changed.
- Build/test commands changed.
- Provider, privacy, caching, or storage behavior changed.
- A repeated workflow needs a runbook.

When to Update State Notes
- The task spans multiple sessions.
- There are non-obvious decisions or failed paths to preserve.
- A handoff file or task file would prevent rediscovery.
