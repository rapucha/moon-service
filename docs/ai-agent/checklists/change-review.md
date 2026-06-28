# Change Review Checklist

Purpose
- Provide a concise pre-finish checklist for AI-assisted changes.

Before Editing
- [ ] Read `AGENTS.md`.
- [ ] Identified whether subagents would help and avoided delegation unless
      authorized.
- [ ] Loaded the relevant context pack for substantial work.
- [ ] Checked current worktree state.
- [ ] Identified likely validation commands.

Change Quality
- [ ] Diff is focused on one concern.
- [ ] Existing user changes were preserved.
- [ ] No unrelated formatting churn.
- [ ] New abstractions match an actual need or existing pattern.
- [ ] Public behavior changes are documented and tested.
- [ ] Privacy and provider implications were considered.
- [ ] Agreement or disagreement with user assumptions is reasoned, not
      appeasing.

Observability
- [ ] New external providers, caches, public endpoints, background jobs,
      feed/export paths, or rate limits have an explicit operator-visibility
      decision.
- [ ] Useful aggregate metrics or request logging were added or updated for
      boundaries that need operator visibility.
- [ ] Metrics and logs avoid raw location queries, precise coordinates,
      provider URLs containing queries, and user-identifying data by default.
- [ ] New operator-visible fields are documented in `backend/README.md` or the
      relevant module docs.

Testing and Validation
- [ ] Focused tests or build command ran.
- [ ] Broader validation ran when touching shared contracts, scoring/window
      behavior, or module structure.
- [ ] `git diff --check` passed.
- [ ] Skipped validation is explicitly reported with the reason.

Documentation
- [ ] Human-owned docs updated if product/API behavior changed.
- [ ] Module READMEs updated if commands or implemented endpoint examples
      changed.
- [ ] Agent docs updated only for workflow, policy, context, or checklist
      changes.
- [ ] Follow-up items are documented if not completed.

Final Response
- [ ] Summary explains what changed.
- [ ] Validation commands and outcomes are listed.
- [ ] Untracked files, generated files, or local IDE-only changes are called
      out when relevant.
- [ ] Remaining risks or next steps are concrete.
