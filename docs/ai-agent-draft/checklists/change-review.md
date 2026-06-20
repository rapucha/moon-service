# Change Review Checklist Draft

Purpose
- Provide a concise pre-finish checklist for AI-assisted changes.

Scope
- Use for code, docs, tests, build files, and project model changes.

Before Editing
- [ ] Read `AGENTS.md`.
- [ ] Identified whether subagents would help and avoided delegation unless
      authorized.
- [ ] Loaded the relevant context pack.
- [ ] Checked current worktree state.
- [ ] Identified likely validation commands.

Change Quality
- [ ] Diff is focused on one concern.
- [ ] Existing user changes were preserved.
- [ ] No unrelated formatting churn.
- [ ] New abstractions match an actual need or existing pattern.
- [ ] Public behavior changes are documented and tested.
- [ ] Privacy and provider implications were considered.

Testing and Validation
- [ ] Focused tests or build command ran.
- [ ] Broader validation ran when touching shared contracts or module
      structure.
- [ ] `git diff --check` passed.
- [ ] Any skipped validation is explicitly reported with the reason.

Documentation
- [ ] README, architecture, API shape, or module docs updated if behavior or
      commands changed.
- [ ] Follow-up items are documented if not completed.

Final Response
- [ ] Summary explains what changed.
- [ ] Validation commands and outcomes are listed.
- [ ] Untracked files, generated files, or local IDE-only changes are called
      out when relevant.
- [ ] Remaining risks or next steps are concrete.
