# AI Agent Operating Docs

Purpose
- Provide active operating guidance for AI agents working on Moon Service.
- Keep agent behavior consistent while the project moves from prototypes into a
  small real backend.
- Point agents to human-owned product and API docs instead of duplicating those
  truths here.

Status
- This is the active AI-agent documentation set.
- Agents should use `docs/ai-agent/`, not `docs/ai-agent-draft/`.
- `docs/ai-agent-draft/` is retained only as review/history material.

Use These Docs
1. Read `AGENTS.md` first. It remains the top-priority project instruction
   file for agent sessions.
2. Read `docs/README.md` to locate human-owned product, API, and architecture
   docs.
3. Read `operating-guide.md` for the standard contribution workflow.
4. Read `policies.md` for guardrails and escalation triggers.
5. Read `github-identity.md` before creating or managing agent-authored
   branches, pull requests, or GitHub comments.
6. Load the relevant context pack before substantial changes:
   - `context-packs/architecture.md`
   - `context-packs/backend-spine.md`
   - `context-packs/api-contract.md`
   - `context-packs/refactor.md`
   - `context-packs/test-authoring.md`
   - `context-packs/fix-build.md`
7. Use checklists before finishing:
   - `checklists/change-review.md`
   - `checklists/api-contract-change.md`
   - `checklists/module-addition.md`
   - `checklists/provider-observability.md`
8. For multi-session or complex work, use `state/task-template.md`.

Canonical Context
- Product direction:
  - `docs/product-notes.md`
  - `docs/mvp-roadmap.md`
- Architecture:
  - `docs/architecture.md`
  - `docs/api-shape.md`
- Domain decisions:
  - `docs/scoring-model.md`
  - `docs/ephemeris-research.md`
  - `docs/weather-provider-research.md`
  - `docs/geocoding-research.md`
- Active backend:
  - `backend/`
- Prototypes:
  - `prototypes/jvm-scoring/`
  - `prototypes/jvm-ephemeris/`

Validation Quick Reference
- Ordinary backend work:
  - `mvn test -pl backend -am`
- JVM scoring prototype work:
  - `(cd prototypes/jvm-scoring && mvn test)`
- Scoring/window migration or prototype retirement:
  - `python3 -B scripts/prototype_contract_parity.py`
- Patch hygiene:
  - `git diff --check`

Prototype Parity Policy
- Backend tests are the primary executable contract for backend behavior.
- `prototypes/jvm-scoring` tests remain useful while backend still depends on
  the scoring prototype.
- `scripts/prototype_contract_parity.py` is a migration tool, not a permanent
  gate.
- Do not run parity checks for ordinary backend work that does not touch
  scoring/window semantics.
