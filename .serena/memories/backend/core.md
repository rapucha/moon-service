# Backend

- `backend/` is the Spring Boot service. `prototypes/jvm-scoring/` supplies the scoring and ephemeris module that the backend currently consumes.
- Intended public MVP surfaces include city or location lookup, opportunity results, shareable pages, RSS/Atom feeds, and iCalendar export.
- Keep fixture replacement incremental. Do not add another live provider, new persistent storage, accounts, an installed client, or local infrastructure without approved scope.
- Read `docs/api-shape.md` for the intended public contract and `backend/README.md` for implemented behavior.
- Read `mem:tech_stack` for module dependencies and `mem:task_completion` for validation.
