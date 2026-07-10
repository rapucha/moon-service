# Moon Service Documentation

Purpose
- Human-facing entry point for Moon Service documentation.
- Keep product, architecture, API, development, and agent guidance discoverable
  without requiring readers to search through agent-only workflows.

Start Here
- Product direction:
  - `docs/product-notes.md`
  - `docs/ui-spec.md`
  - `docs/design/README.md`
  - `docs/mvp-roadmap.md`
- Architecture and API:
  - `docs/architecture.md`
  - `docs/api-shape.md`
  - `docs/self-hosting-alpha-plan.md`
  - `docs/container-image-publication.md`
  - `deployment/raspberry-pi/README.md`
- Domain decisions:
  - `docs/scoring-model.md`
  - `docs/ephemeris-research.md`
  - `docs/weather-provider-research.md`
  - `docs/geocoding-research.md`
- Implementation modules:
  - `backend/README.md`
  - `prototypes/jvm-scoring/README.md`
  - `prototypes/jvm-ephemeris/README.md`
  - `live-tests/README.md`
- AI-agent operating docs:
  - `docs/ai-agent/README.md`
  - `docs/ai-agent/github-identity.md`

Current Canonical API Location
- `docs/api-shape.md` is currently the human-owned API design document.
- It includes intended public response examples and status semantics.
- Current implemented fixture-backed endpoint usage is documented in
  `backend/README.md`.

Documentation Ownership
- Human-owned docs hold product and API truth.
- AI-agent docs hold operating procedures, context-loading workflows,
  guardrails, and checklists.
- Agent docs should link to canonical docs rather than duplicate or redefine
  product behavior.

Future Organization Candidate
- If `docs/api-shape.md` grows too large, split it into:
  - `docs/api/opportunities-api.md`
  - `docs/api/examples/preview-ok.json`
  - `docs/api/examples/preview-invalid.json`
  - `docs/api/examples/preview-ambiguous.json`
