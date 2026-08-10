# Conventions

- Follow `AGENTS.md`, `.agents/review-policy.md`, and the relevant files under `docs/ai-agent/`; these remain authoritative when a memory differs.
- Keep the backend small and web-first. Prefer direct tradeoffs to speculative abstractions.
- Do not add fallback paths, public overloads, extension points, or compatibility APIs without the production-use evidence required by `.agents/review-policy.md`.
- Never permanently store lookup locations except for the documented, disabled, bounded city-level calibration-feedback exception.
- Preserve unrelated dirty worktree changes and use plain technical prose.
