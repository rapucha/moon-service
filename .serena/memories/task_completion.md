# Completion

- Follow `AGENTS.md` and `.agents/review-policy.md`; they define the current issue, branch, local-feedback, review, publication, and handoff requirements.
- Documentation-only changes require `git diff --check`. Ordinary backend changes require `mvn test -pl backend -am`.
- Scoring or window changes also require the scoring prototype tests and CLI preview. Run prototype parity only for migration behavior or prototype retirement.
- Apply `mem:java_lsp_safety` after Java indexing and before final Maven validation.
- Preserve unrelated changes. Stage every intended file and leave unrelated, generated, IDE-only, and intentionally excluded files unstaged.
