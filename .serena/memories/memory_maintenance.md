# Memory Maintenance

## Discovery

- Treat `mem:core` as the root of a progressively disclosed memory graph.
- Group related memories with topic paths such as `backend/core`.
- Write references as a memory name prefixed with `mem:`, for example `mem:backend/core`, and explain what the referenced memory contains.
- Use Serena's rename tool when renaming memories so references are updated. Run `serena memories check` after structural changes.

## Content

- Use dense agent notes and terse bullets rather than prose documentation.
- Keep repository documents authoritative. Memories should point to them instead of duplicating volatile detail.
- After an accepted change that is major and durable, check only the relevant tracked memories before final staged review.
- The same pull request may correct or remove an existing tracked memory without another owner instruction only when the accepted change made that memory false or materially incomplete.
- Add or update a memory only when the information is stable, non-obvious, and costly to rediscover.
- A new memory and any edit outside that same-pull-request rule still require explicit owner instruction.
- Do not store one-off task notes, generic framework knowledge, volatile line-level details, secrets, personal data, or machine-specific paths.
- Split a memory when one name no longer describes its contents clearly. Delete obsolete guidance instead of preserving it as current context.
