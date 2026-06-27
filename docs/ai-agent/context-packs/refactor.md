# Context Pack - Refactor

Purpose
- Guide behavior-preserving refactors with low churn and clear validation.

When To Use
- Renaming or moving code.
- Extracting provider interfaces or services.
- Reducing duplication.
- Improving test seams.
- Moving prototype concepts into backend-owned code.

Principles
- Preserve behavior unless the user asked for a behavior change.
- Add baseline tests before risky movement.
- Refactor one boundary at a time.
- Keep package/module moves narrow and justified.
- Avoid unrelated style or formatting churn.

Recommended Sequence
1. Identify target and risk.
   - Is this public API, scoring, provider, or pure internal structure?
   - Does it affect backend behavior or scoring/window semantics?

2. Capture current behavior.
   - Add or run existing tests.
   - For HTTP behavior, prefer backend tests.
   - For scoring/window generation, use scoring prototype tests and parity only
     when those semantics are touched.

3. Make the smallest useful structural change.
   - Extract method/class.
   - Introduce interface.
   - Move ownership boundary.
   - Replace direct dependency with injected dependency.

4. Validate locally.
   - Module tests first.
   - Broader checks only when contracts, scoring, or module boundaries shifted.

5. Document changes if boundaries moved.

Risk Mitigations
- Keep public response JSON stable unless following API contract workflow.
- Keep exception and status behavior stable.
- Avoid cross-module renames unless necessary.
- Do not delete prototypes until backend coverage makes them redundant.

Validation
- Backend refactor:
  - `mvn test -pl backend -am`
- Scoring/window refactor:
  - `(cd prototypes/jvm-scoring && mvn test)`
  - `python3 -B scripts/prototype_contract_parity.py`
- Always:
  - `git diff --check`
