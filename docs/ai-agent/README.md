# AI Agent Operating Docs

## Purpose

These files tell AI agents how to work on Moon Service. They keep agent work
consistent while the project moves from prototypes to a small backend. They
link to the product and API documents instead of copying those decisions.

## Status

Agents should use `docs/ai-agent/` as the active agent documentation, not
`docs/ai-agent-draft/`. The draft directory remains only as review and history
material.

## What To Read

1. Read `AGENTS.md` first. It contains the highest-priority project
   instructions for an agent session.
2. Read `docs/README.md` to find the product, API, and architecture documents.
3. Read `operating-guide.md` for the standard contribution workflow.
4. Read `policies.md` for guardrails and escalation triggers.
5. Read [`plain-technical-writing.md`](plain-technical-writing.md) before you
   write or review technical prose.
6. Read `github-identity.md` before you create or manage an agent-authored
   branch, pull request, or GitHub comment.
7. Before a substantial change, load the context pack that matches the work:
   - `context-packs/architecture.md`
   - `context-packs/backend-spine.md`
   - `context-packs/api-contract.md`
   - `context-packs/refactor.md`
   - `context-packs/test-authoring.md`
   - `context-packs/fix-build.md`
8. Before you finish, use the relevant checklist:
   - `checklists/change-review.md`
   - `checklists/api-contract-change.md`
   - `checklists/module-addition.md`
   - `checklists/provider-observability.md`
9. Use `state/task-template.md` for complex work or work that spans sessions.

## Canonical Project Context

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

## Validation Commands

- Ordinary backend work:
  - `mvn test -pl backend -am`
- JVM scoring prototype work:
  - `(cd prototypes/jvm-scoring && mvn test)`
- Scoring or window migration, or prototype retirement:
  - `python3 -B scripts/prototype_contract_parity.py`
- Patch hygiene:
  - `git diff --check`

## Prototype Parity

Backend tests are the main executable contract for backend behavior. The
`prototypes/jvm-scoring` tests remain useful while the backend depends on that
prototype.

Use `scripts/prototype_contract_parity.py` only for migration work. Do not run
it as a permanent gate or for ordinary backend work that does not change
scoring or window behavior.
