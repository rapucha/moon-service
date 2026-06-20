# Context Pack - API Contract

Purpose
- Provide a safe workflow for changing public request/response behavior.

When To Use
- Adding, removing, or changing routes.
- Changing request fields, query parameters, status values, error response
  shape, feeds, or `.ics` exports.
- Changing the meaning of an existing response field.

Canonical Contract Sources
- Design contract: `docs/api-shape.md`.
- Current implemented backend behavior: backend tests and `backend/README.md`.
- Current fixture input: `prototypes/jvm-scoring/fixtures/prague-preview-request.json`.
- Prototype reference only: `prototypes/spring-preview/`.

Workflow
1. Identify the current and desired contract.
   - Is this moving from fixture request to query-based lookup?
   - Is it additive or breaking?
   - Who consumes it: browser page, share link, feed, calendar client, tests?

2. Update human-owned docs.
   - Update `docs/api-shape.md` for public contract decisions.
   - Update `backend/README.md` for currently implemented endpoint examples.
   - If API examples are later split, update `docs/api/examples/` and link from
     the API overview.

3. Implement with explicit error semantics.
   - Keep product states separate from transport failures.
   - Do not return empty opportunities for dependency failures.
   - Keep unknown or ambiguous locations distinct from invalid input.

4. Test the contract.
   - Happy path.
   - Invalid request.
   - Unsupported or not-found location.
   - Ambiguous location when available.
   - Dependency unavailable once providers exist.
   - Serialization shape for feeds and calendars when introduced.

5. Validate.
   - `mvn test -pl backend -am`
   - Scoring tests and parity only if opportunity generation semantics changed.
   - `git diff --check`

Compatibility Guidance
- Prefer additive optional fields.
- Do not rename or remove public fields casually.
- If a breaking change is intentional, document the migration path and update
  all examples.
- Keep stable status values:
  - `ok`
  - `ambiguous_location`
  - `location_not_found`
  - `invalid_request`
  - `temporarily_unavailable`
  - `rate_limited`

Privacy and Abuse Considerations
- Validate `q` before provider calls.
- Reject empty or unreasonably long input.
- Strip or reject control and bidirectional control characters.
- Normalize whitespace for lookup/cache keys while preserving display intent
  only where safe.
- Avoid raw query text in structured logs.
