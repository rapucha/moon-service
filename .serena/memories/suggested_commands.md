# Commands

- Backend reactor tests: `mvn test -pl backend -am`.
- Scoring prototype tests: `(cd prototypes/jvm-scoring && mvn test)`.
- Prototype CLI fixture preview: `(cd prototypes/jvm-scoring && mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.classpathScope=test -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json")`.
- Prototype parity for scoring/window migration or prototype retirement: `python3 -B scripts/prototype_contract_parity.py`.
- Documentation-only validation: `git diff --check`.
- After Java indexing, apply `mem:java_lsp_safety` before treating Maven results as final.
