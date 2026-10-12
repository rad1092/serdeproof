# Changelog

## 0.1.1 — 2026-10-12

- Refuse report destinations overlapping declared fixtures, mapper configuration, Java executables, and classpath files/directories even with `--force`; recheck inputs before publication.
- Add opt-in `--fail-on-untested` and a library overload for CI gates requiring initial semantic coverage for every fixture. Coverage failures agree across exit status, JSON and JUnit.
- Fix process ownership during adapter cleanup. An exited JVM's historical parent PID relationships on Windows could make unrelated processes, including the supervising JVM, appear in descendant enumeration.
- Enumerate only while the owned root is alive; validate creation timestamps along the parent chain and recheck each observed descendant's identity before termination. Refuse current-JVM, older, reused or unverifiable identities. Terminate the root via its owned `Process` handle instead of looking its PID up again.
- Add deterministic stale-parent/PID-reuse regressions while retaining the real timeout, cancellation and JVM-shutdown descendant tests. Cleanup remains best effort and is not a hostile-process sandbox.
- Add a no-build portable-release walkthrough and bounded infrastructure status diagnostics for failed installed-artifact verification. Existing library overload behavior, report schema and serializer behavior remain compatible.

## 0.1.0 — 2026-10-08

Initial release: dependency-free Java adapter API; separately supervised baseline/candidate JVMs; acceptance, JSON path, domain observation and four-way producer-consumer checks; exact scoped expected changes with expiry; deterministic privacy-conscious JSON/JUnit reports with reproducibility fingerprints; pinned Jackson 2/3 examples and installed-artifact smoke.

The public API and report schema are versioned as an initial 0.x release. Artifact signatures, notarization, Maven Central publication and semantic equivalence guarantees are not provided.
