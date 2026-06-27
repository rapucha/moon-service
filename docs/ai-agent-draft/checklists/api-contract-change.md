# API Contract Change Checklist Draft

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
      `docs/api-shape.md`, backend tests, prototype fixtures, or README.
- [ ] Compatibility impact assessed: additive, behavior change, or breaking.
- [ ] Privacy and abuse impact considered for user-supplied inputs.

Design
- [ ] `docs/api-shape.md` updated if public behavior changed.
- [ ] Status codes and response `status` values remain consistent.
- [ ] Error model distinguishes invalid input from dependency failures.
- [ ] Examples updated.

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
- [ ] Scoring/parity commands if opportunity output changed.
- [ ] `git diff --check`

Final Summary
- [ ] Compatibility impact stated.
- [ ] Validation evidence included.
- [ ] Deferred migration or follow-up items listed.
