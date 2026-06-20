# Moon Service AI Agent Draft Guide

Purpose
- Draft a thorough AI-targeted operating guide for Moon Service before moving
  the content into durable project instructions.
- Capture transferable practices from the archived agent documentation while
  removing unrelated architecture, compliance, and organization-specific detail.
- Give future agents a predictable way to gather context, plan changes,
  validate work, and hand off state.

Status
- Superseded by `docs/ai-agent/`.
- Do not use this folder as operating guidance.
- Retained only for review history and feedback traceability.

How Agents Should Use This Draft
1. Start with this file to identify the right workflow.
2. Read `operating-guide.md` for the general contribution process.
3. Read `policies.md` for guardrails and escalation triggers.
4. Load the relevant context pack before planning:
   - `context-packs/architecture.md`
   - `context-packs/backend-spine.md`
   - `context-packs/api-contract.md`
   - `context-packs/refactor.md`
   - `context-packs/test-authoring.md`
   - `context-packs/fix-build.md`
5. Use checklists before finishing:
   - `checklists/change-review.md`
   - `checklists/api-contract-change.md`
   - `checklists/module-addition.md`
6. For larger efforts, copy `state/task-template.md` into a task-specific note
   and maintain command evidence and decisions.

Repository Anchors
- Product and scope:
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
- Active implementation:
  - `backend/`
  - `prototypes/jvm-scoring/`
  - `prototypes/spring-preview/`
- Historical and validation prototypes:
  - `prototypes/jvm-ephemeris/`
  - `scripts/`

Current Validation Commands
- Backend spine:
  - `mvn test -pl backend -am`
- Spring preview prototype:
  - `mvn test -pl prototypes/spring-preview -am`
- JVM scoring prototype:
  - `(cd prototypes/jvm-scoring && mvn test)`
  - `(cd prototypes/jvm-scoring && mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.classpathScope=test -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json")`
- Cross-prototype parity:
  - `python3 -B scripts/prototype_contract_parity.py`
- Formatting and patch hygiene:
  - `git diff --check`

Adoption Notes
- Keep project-specific rules in `AGENTS.md` concise and directive.
- Keep longer workflows and checklists in docs where they can evolve.
- Avoid turning this draft into a heavyweight process before the MVP shape is
  stable.
