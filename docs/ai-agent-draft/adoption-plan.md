# Adoption Plan Draft

Purpose
- Define how to move useful draft guidance into durable Moon Service docs after
  review.

Recommended Adoption Order
1. Keep this folder as review material until the user approves the shape.
2. Fold short, high-priority rules into `AGENTS.md`.
3. Keep longer workflow material under `docs/ai-agent/` or another approved
   folder.
4. Add links from `README.md` only after the docs are accepted as active.
5. Remove or archive draft-only material once active docs exist.

High-Value Rules To Promote To `AGENTS.md`
- Start with relevant context packs for substantial work.
- Report whether subagents would help; do not spawn without authorization.
- Keep backend growth small and web-first.
- Do not add persistence, accounts, deployment, Android, or live providers
  without explicit request and documentation.
- Run focused validation and report command evidence.
- Treat public API changes and privacy/storage changes as escalation points.

Useful Files To Keep Long-Term
- `operating-guide.md`
- `policies.md`
- `context-packs/backend-spine.md`
- `context-packs/api-contract.md`
- `context-packs/test-authoring.md`
- `context-packs/fix-build.md`
- `checklists/change-review.md`
- `checklists/api-contract-change.md`
- `checklists/module-addition.md`
- `state/task-template.md`

Draft Material That May Be Too Heavy For Now
- Persistent task board.
- Prompt template system with snippet imports.
- Formal PR checklist copying unless this project moves to regular PR review.
- Coverage/static-analysis policies before those gates exist.

Open Questions
- Should active agent docs live under `docs/ai-agent/` or remain summarized in
  `AGENTS.md`?
- Should session handovers stay informal, or should large tasks use
  `state/task-template.md`?
- Should the backend module adopt a stricter verification profile now, or wait
  until more code exists?
- When should the Spring preview prototype be retired?
