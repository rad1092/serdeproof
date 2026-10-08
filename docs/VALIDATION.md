# Reproduction and validation

This file describes the release checks and their scope. The final GitHub release attaches checksums and a machine-readable validation record with its exact Git commit and CI run URL. A configured workflow is not evidence of a completed OS test: use that record and the linked run.

## Local verification

On macOS arm64, Eclipse Temurin 17.0.20.1+1 and Maven 3.9.11 were downloaded from official Adoptium/Maven sources and their published checksums verified. See [toolchain provenance](toolchain-provenance.json). No system Java installation, new credential, paid service or modification of another workspace project was required.

```sh
./mvnw -B -ntp clean install
python3 scripts/smoke.py
python3 scripts/audit.py --output target/audit.json
```

`clean install` runs the lifecycle through `verify` once, then installs the API, core, CLI, demos, sources, Javadocs and ZIP into the selected Maven local repository. The release candidate's **68 tests passed with zero failures, errors or skips** on the local Mac. Maintained tests exercise binary framing, strict UTF-8 JSON, huge/exact numeric values, pointer escaping, missing/null differences, fingerprints, rules, JVM failures and cleanup, and actual Jackson DTO/module behavior.

The installed-artifact smoke executes five maintained manifests, compiles a separate application adapter and library caller against installed JARs, then unpacks and runs the release outside the checkout. It checks deterministic JSON/JUnit bytes and default ID privacy. The custom adapter JAR path includes Korean, spaces and emoji; the extracted release path includes Korean and spaces. See the JDK caller-startup caveat in [SECURITY](../SECURITY.md).

| Synthetic scenario | Expected result |
|---|---|
| `demo.json` | Exit 1; 17 fixtures, 12 unexpected differences, 5 without two-sided semantic coverage |
| `compatible.json` | Exit 0; 5 fixtures, no differences or untested fixtures |
| `jackson-config-change.json` | Exit 1; 4 fixtures, 15 differences |
| `same-version-loss.json` | Exit 1; one fixture, four roundtrip losses despite identical versions |
| `jackson-reviewed.json` | Exit 1; exactly one reviewed difference, other findings remain blocking |

Historical fixtures for Jackson issues #3906 and #5729 pass their focused tests on the pinned current dependencies. They document regressions to guard against, not claims that current Jackson releases still contain those bugs. The examples are synthetic and do not measure production adoption or corpus representativeness.

## Security, dependency and license evidence

[dependency-audit.json](evidence/dependency-audit.json) records 14 resolved runtime/compile/test artifacts. The audit compares CycloneDX SHA-256 hashes of the artifacts actually used in the build with bytes downloaded from the official Maven Central host, checks Central's transfer checksum, collects upstream license declarations and queries OSV by exact Maven coordinates. It fails on missing hashes/licenses, checksum mismatch, known advisories, an incomplete/paginated response, or a failed service call. Build-plugin dependencies and the JDK are outside this inventory; no complete supply-chain safety claim is made.

The audit also performs a limited credential-pattern scan. A separate checksum-verified official Gitleaks binary scans the release source and Git history; [gitleaks.json](evidence/gitleaks.json) records its scope and version. Heuristic scans and known-advisory data cannot prove absence of secrets or unknown vulnerabilities.

Distribution inspection checks ZIP CRCs, fixed archive timestamps, explicit source allowlists, executable wrapper mode, installed/build artifact byte equality and retention of Jackson/FastDoubleParser/Schubfach license notices. [THIRD_PARTY](THIRD_PARTY.md) and the root notice file explain embedded licenses. Maven can print non-fatal missing-Javadoc and upstream CycloneDX schema-keyword/shading warnings; these are not compiler or test failures.

## Multi-OS gate and release

[CI](../.github/workflows/ci.yml) runs `clean install` and installed-artifact smoke on Ubuntu, macOS and Windows, each with Temurin Java 17 and 21. A separate Ubuntu job builds the aggregate BOM and runs the dependency/license/source scan. Actions are pinned to upstream commit SHAs. Test XML and compact verification files are uploaded as job artifacts.

Before creating the release, the maintainer checks all seven jobs on the exact pushed source commit. The release's `validation.json` records that commit and run URL; `SHA256SUMS` covers the published artifacts. Download verification compares uploaded files with local artifacts. GitHub-hosted OS tests do not cover every distribution, CPU architecture, JDK vendor or future version. Gradle coordinates are documented but a separate Gradle build was not executed.

No Maven Central upload, signature, notarization, performance benchmark, user interview, production trial or semantic-equivalence certification is claimed. GitHub releases are immutable by convention here, not cryptographically authenticated. To roll back, retain a previous source tag/release artifact and remove the gate from CI temporarily; SerdeProof does not modify your DTOs or stored payloads.

## Fixes discovered during verification

- A JDK 17 supplementary-Unicode classpath issue required a temporary manifest launcher for adapter JVMs.
- A test fixture worked with `mvn test` but omitted API classes after packaging; the fixture now handles directory and JAR code sources.
- Strict UTF-8 validation, bounded metadata, explicit classpath fingerprints and CLI path-alias protection were added after independent review.
- Default reports omit raw adapter errors and values, including during malformed responses and crashes.

Large temporary downloads, diagnostic probe sources, copied source snapshots and disposable smoke directories are removed. Small tests and synthetic fixtures remain in source. Compact validation manifests preserve outcomes without retaining generated dumps.
