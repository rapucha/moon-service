# Context Pack - Fix Build

Purpose
- Diagnose and fix compile, test, project model, or validation failures without
  chasing downstream noise.

When To Use
- Maven build fails.
- Tests fail.
- IntelliJ/module source roots are wrong.
- Prototype parity fails.
- `git diff --check` reports whitespace issues.

Triage Workflow
1. Reproduce the smallest relevant failure.
   - Backend: `mvn test -pl backend -am`
   - Spring preview: `mvn test -pl prototypes/spring-preview -am`
   - Scoring: `(cd prototypes/jvm-scoring && mvn test)`
2. Capture the first failing error.
   - Do not chase later failures until the first one is understood.
3. Classify the failure.
   - Compile error.
   - Test assertion failure.
   - Spring context or port issue.
   - Missing local artifact or Maven reactor setup.
   - Prototype parity drift.
   - IDE-only project model problem.
4. Form a concrete hypothesis and make a narrow fix.
5. Re-run the same failing command.
6. Broaden validation only after the narrow failure is green.

Common Fix Patterns
- Missing dependency between modules:
  - Prefer adding the module to the root Maven reactor and using `-pl ... -am`.
- IntelliJ says source is outside module root:
  - Ensure the root `pom.xml` is imported as a Maven project.
  - Ensure the module's `src/main/java` and `src/test/java` are source roots.
  - Avoid relying on untracked IDE files as the only source of truth.
- Spring test starts embedded server:
  - In restricted environments, local port binding may require elevated
    permissions.
- Prototype artifact missing:
  - Use reactor build where possible instead of manual local install.

Validation
- Re-run the original failing command.
- Run `git diff --check`.
- If project model changed, run a Maven command from the root to prove the
  build does not depend on IDE metadata.

Reporting
- State root cause.
- State exact fix.
- State command evidence.
- Mention any remaining IDE/manual action separately from code changes.
