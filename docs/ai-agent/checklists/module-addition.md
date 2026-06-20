# Module Addition Checklist

Purpose
- Keep new modules intentional, discoverable, and integrated into the build.

Scope
- New Maven modules, prototype modules, script packages, or future frontend
  modules.

Planning
- [ ] Module purpose is clear.
- [ ] Module is necessary now and not premature scaffolding.
- [ ] Ownership boundary is documented.
- [ ] Dependencies are identified.
- [ ] Privacy/storage/provider implications are considered.

Scaffolding
- [ ] Standard layout used where applicable:
      `src/main/java`, `src/test/java`, resources only when needed.
- [ ] Module has a local README if it is user-facing or non-obvious.
- [ ] Module is added to root build configuration if it should compile in the
      reactor.
- [ ] Build outputs are ignored.
- [ ] IDE metadata is not the only way the module compiles.

Implementation
- [ ] Uses existing project Java/Spring/Maven patterns.
- [ ] Does not introduce database, deployment, account, Android, or live
      provider infrastructure unless explicitly requested.
- [ ] Keeps fixture/fake behavior obvious when real providers are not wired.

Testing
- [ ] Module has focused tests.
- [ ] Root/module build command works.
- [ ] Dependent modules compile through the reactor.

Documentation
- [ ] Top-level README repository map updated.
- [ ] `docs/README.md` updated if the module is important to humans.
- [ ] `AGENTS.md` verification commands updated if durable.
- [ ] Architecture docs updated if boundaries changed.

Validation
- [ ] Module-scoped build/test command passed.
- [ ] Relevant dependent module tests passed.
- [ ] `git diff --check` passed.
