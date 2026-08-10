# Java LSP Safety

- Serena 1.7.0 uses JDTLS autobuild. The test source `prototypes/jvm-scoring/src/test/java/dev/moonservice/scoringprototype/OpportunityHardFilterTest.java` declares package `dev.moonservice.scoringprototype.window` despite being one directory above that package path.
- JDTLS can emit the wrong-path artifact `prototypes/jvm-scoring/target/test-classes/dev/moonservice/scoringprototype/OpportunityHardFilterTest.class`. It can survive ordinary test compilation and interfere with test discovery.
- After Java indexing, stop Serena/JDTLS before final Maven validation. Prefer `mvn clean test -pl backend -am`, then assert that the wrong-path artifact is absent.
- A narrow cleanup may remove only the wrong-path artifact above. Do not remove the correct class under `target/test-classes/dev/moonservice/scoringprototype/window/`.
- Moving the source to match its package is separate issue-backed work.
