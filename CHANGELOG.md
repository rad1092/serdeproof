# Changelog

## 0.1.1 — 2026-10-12

- Fix process ownership during adapter cleanup. An exited JVM's historical parent PID relationships on Windows could make unrelated processes, including the supervising JVM, appear in descendant enumeration.
- Enumerate only while the owned root is alive; validate creation timestamps along the parent chain and recheck each observed descendant's identity before termination. Refuse current-JVM, older, reused or unverifiable identities. Terminate the root via its owned `Process` handle instead of looking its PID up again.
- Add deterministic stale-parent/PID-reuse regressions while retaining the real timeout, cancellation and JVM-shutdown descendant tests. Cleanup remains best effort and is not a hostile-process sandbox.
- Add a no-build portable-release walkthrough and bounded infrastructure status diagnostics for failed installed-artifact verification. Public API, report schema and serializer behavior are unchanged.

## 0.1.0 — 2026-10-08

Initial release: dependency-free Java adapter API; separately supervised baseline/candidate JVMs; acceptance, JSON path, domain observation and four-way producer-consumer checks; exact scoped expected changes with expiry; deterministic privacy-conscious JSON/JUnit reports with reproducibility fingerprints; pinned Jackson 2/3 examples and installed-artifact smoke.

The public API and report schema are versioned as an initial 0.x release. Artifact signatures, notarization, Maven Central publication and semantic equivalence guarantees are not provided.
