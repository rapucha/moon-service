# AI Agent Draft Feedback

Purpose
- Track user feedback on `docs/ai-agent-draft/` before applying changes.
- Keep critique separate from the draft content so it can be reviewed and
  incorporated deliberately later.

## 2026-06-20 - Prototype Parity

Feedback
- Prototype parity should matter less as the real backend starts owning the
  executable contract.
- The prototypes were intentionally primitive and do not closely resemble the
  real service shape.
- Treating every prototype parity check as a durable gate would overfit future
  backend work to temporary scaffolding.

Conclusion To Apply Later
- Backend tests should become the primary contract.
- `prototypes/jvm-scoring` tests remain useful temporarily while backend still
  depends on the scoring prototype.
- Prototype parity scripts should be migration tools, not permanent gates.
- Historical prototypes should be reference material only.
- Run `scripts/prototype_contract_parity.py` only when changing scoring/window
  generation, comparing migration behavior, or retiring a prototype.
- Stop requiring parity checks for ordinary backend work that does not touch
  scoring/window semantics.
- Mark `prototypes/spring-preview` as superseded once backend endpoint tests
  cover equivalent behavior.
- Eventually move useful scoring logic and tests into real backend/domain
  modules, then archive or delete obsolete prototypes.

## 2026-06-20 - Avoid Appeasing Agreement

Feedback
- Phrases such as "your instinct is right" can sound like social appeasement
  rather than technical evaluation.
- The project guidance should preserve honest critique and make disagreement
  explicit when a user assumption is weak or risky.
- The issue is not agent personality; it is loss of useful technical criticism
  through overly agreeable phrasing.

Conclusion To Apply Later
- Agent docs should instruct agents to state technical judgments directly.
- Agreement should be paired with evidence or reasoning, not generic
  affirmation.
- Disagreement should be plain, specific, and actionable.
- Agents should separate:
  - what they agree with
  - the caveats or counterarguments
  - the validation path or decision needed
- Prefer phrasing such as:
  - "I agree with reducing parity checks, with one caveat..."
  - "That assumption is risky because..."
  - "I would not do that yet; the missing piece is..."
- Avoid generic appeasing phrases such as:
  - "your instinct is right"
  - "you are absolutely right"
  - "great idea"
  - agreement without reasoning

## 2026-06-20 - Human-Owned Docs And Discoverability

Feedback
- API examples and product/API truth should not be buried primarily in
  AI-agent docs.
- Humans need predictable, audience-oriented documentation locations because
  the repo already has many docs.
- Agent docs are useful, but they should not become the canonical source for
  product behavior, API contracts, or examples.

Conclusion To Apply Later
- Separate docs by audience and purpose.
- Keep a human-facing docs hub, such as `docs/README.md`, as the main
  navigation entry point.
- Keep canonical product/API docs in human-owned locations.
- Use agent docs for operating procedures, context-loading workflows,
  guardrails, and checklists.
- Agent docs should link to canonical human docs instead of duplicating or
  owning product/API truth.
- Consider moving or splitting current API material toward:
  - `docs/api/preview-api.md`
  - `docs/api/examples/preview-ok.json`
  - `docs/api/examples/preview-invalid.json`
  - `docs/api/examples/preview-ambiguous.json`
- Update draft checklist wording from vague "examples updated" to something
  explicit, such as:
  - "Public API examples updated in `docs/api/examples/` and linked from
    `docs/api/preview-api.md`."
