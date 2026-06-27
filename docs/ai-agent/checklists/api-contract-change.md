# API Contract Change Checklist

Purpose
- Ensure public API and export changes are intentional, documented, and tested.

Scope
- Backend HTTP routes.
- Request and response fields.
- Status values and error contracts.
- Shareable pages, Atom/RSS feeds, and `.ics` exports.

Pre-flight
- [ ] Motivation documented.
- [ ] Current contract source identified:
      `docs/api-shape.md`, backend tests, backend README, or prototype fixtures.
- [ ] Compatibility impact assessed: additive, behavior change, or breaking.
- [ ] Privacy and abuse impact considered for user-supplied inputs.

Design
- [ ] Public API behavior updated in `docs/api-shape.md`.
- [ ] Public examples updated in the canonical human-owned API docs.
      Currently this means `docs/api-shape.md`; if examples are later split,
      update `docs/api/examples/` and link them from the API overview.
- [ ] Implemented endpoint examples updated in module READMEs or fixtures.
- [ ] Status codes and response `status` values remain consistent.
- [ ] Error model distinguishes invalid input from dependency failures.

Implementation
- [ ] Controller/request validation updated.
- [ ] Response serialization tested.
- [ ] Provider failure behavior represented explicitly.
- [ ] No raw user query logging introduced.

Testing
- [ ] Happy path test.
- [ ] Invalid request test.
- [ ] Not found or unsupported location test.
- [ ] Ambiguous location test where applicable.
- [ ] Dependency failure or rate-limit test once providers exist.
- [ ] Feed/calendar serialization tests once those exports exist.

Validation
- [ ] `mvn test -pl backend -am`
- [ ] Scoring tests and parity only if opportunity generation semantics changed.
- [ ] `git diff --check`

Final Summary
- [ ] Compatibility impact stated.
- [ ] Validation evidence included.
- [ ] Deferred migration or follow-up items listed.
