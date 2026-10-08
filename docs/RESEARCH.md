# Research and product scope

Research date: 2026-10-08. Sources below are upstream issue discussions, project documentation, source repositories, and Maven Central. This is evidence of a concrete engineering problem, not a market-size estimate or a claim that teams will pay for this product.

## Problem observed in existing software

A dependency upgrade can compile successfully while changing which payloads a mapper accepts, which values a DTO receives, or what a producer writes. Tests using a default mapper or only comparing public Java signatures do not necessarily exercise application-specific modules, visibility settings, coercion rules, and DTOs.

1. **A shared library multiplies the upgrade risk.** Jackson databind [issue #3906](https://github.com/FasterXML/jackson-databind/issues/3906) reports that deserializing a record with an explicit visibility configuration worked in 2.14.2 and failed in 2.15.0. The [reporter's follow-up](https://github.com/FasterXML/jackson-databind/issues/3906#issuecomment-1532159726) describes a foundation library relied upon by at least 50 services. The issue was closed. It supports testing real mapper configuration, not a claim of an unresolved defect in current Jackson.
2. **Date parsing can regress without a DTO API change.** [Issue #5729](https://github.com/FasterXML/jackson-databind/issues/5729) reports that Jackson 2.21.1 treated a signed numeric timestamp string, such as `"-1383043669935"`, as ISO-8601 input. [PR #5730](https://github.com/FasterXML/jackson-databind/pull/5730), merged on 2026-03-06, fixes that classification and adds regression tests. This is a historical regression case. Merely running its input on current versions does not reproduce the old failure.
3. **Some behavior changes are intentional.** The [Jackson 3.0 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0) document changed defaults for unknown properties, trailing tokens, primitive nulls, enum `toString()` use, and date/duration timestamp output. These justify explicit expected-change rules: a detected difference can be an accepted migration decision rather than a library bug.

These examples support a narrow initial user: a Java platform or service team upgrading serialization beneath existing DTOs and persisted messages. They do not establish that every upgrade is unsafe, or that the observed cases represent all users.

## Alternatives and overlap

| Existing approach | What it already does | Why a team might still use SerdeProof |
| --- | --- | --- |
| [japicmp](https://github.com/siom79/japicmp) | Compares JAR class APIs, source/binary compatibility, and annotations. It also evaluates `Serializable` changes against the Java Object Serialization specification. | Run actual Jackson or other serializer code against application payloads. The claim is not that japicmp ignores all serialization compatibility. |
| [OpenDiffy](https://github.com/opendiffy/diffy) | Proxies requests to running candidate, primary, and secondary services, compares responses, and estimates nondeterministic noise. | Exercise local DTO adapters without deploying complete services. OpenDiffy is the current comparison; the [Twitter repository](https://github.com/twitter-archive/diffy) is archived. |
| [Verify](https://github.com/VerifyTests/Verify) | Provides a .NET snapshot workflow that serializes test results and compares them with approved files. | Supply a Java-specific adapter and corpus workflow. Verify is a conceptual alternative, not a Java package. |
| [ApprovalTests.Java](https://github.com/approvals/ApprovalTests.Java) | Provides Java approval assertions, including JSON and complex objects, integrated with common test frameworks. | Package version isolation, cross-version consumption, limits, fingerprints, and expiring exceptions together. A team can build a similar harness around ApprovalTests. |
| [JsonUnit](https://github.com/lukas-krecan/JsonUnit) | Provides rich JSON assertions, JSONPath selection, numeric comparison, ignored paths, and custom matchers. | Obtain and classify evidence from isolated serializers before comparing JSON. JSON path reporting alone is not a distinctive capability. |
| [OpenRewrite Jackson 2-to-3 recipe](https://docs.openrewrite.org/recipes/java/jackson/upgradejackson_2_3) | Migrates source and dependency declarations, including package, class, exception, and method changes. | Evaluate the behavior of the resulting old and new builds on a local corpus. SerdeProof does not transform source. |
| [Spring Boot migration defaults](https://docs.spring.io/spring-boot/how-to/spring-mvc.html#howto.spring-mvc.customize-jackson-objectmapper) | Offers `spring.jackson.use-jackson2-defaults=true` to bring the auto-configured Jackson 3 mapper closer to Boot's earlier defaults. | Check the application's actual configured mappers and DTOs after choosing its migration policy. A raw Jackson-default demo is not a forecast for a Spring Boot application. |
| [Confluent Schema Registry](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html) | Checks evolving schemas under backward, forward, full, and transitive compatibility policies. | Observe actual DTO/mapper execution. Schema compatibility and executable behavior checks complement each other. |
| [cvent/jackson-compatibility-test](https://github.com/cvent/jackson-compatibility-test) | Implements a custom enum deserializer to smooth older Jackson version differences. | Run a general fixture corpus across two user-owned implementations. The similar repository name does not describe a general-purpose migration runner. |

Repository searches also surfaced serializer benchmarks, such as [java-serialization-compare](https://github.com/stellhub/java-serialization-compare), which compare encoded size and performance. SerdeProof does not benchmark serializer throughput.

This was a bounded search, not an exhaustive claim that no competing tool exists. The strongest substitute is a team's existing integration-test harness with carefully isolated dependencies and representative fixtures.

The overlap is substantial. ApprovalTests already has [`JsonJackson3Approvals`](https://github.com/approvals/ApprovalTests.Java/blob/master/approvaltests/src/main/java/org/approvaltests/JsonJackson3Approvals.java), including a mapper-builder customizer. JsonUnit already offers [Jackson 2 and Jackson 3 mapper-provider SPIs](https://github.com/lukas-krecan/JsonUnit#jackson-object-mapper-customization). SerdeProof must not claim to be the first way to test Jackson 3, customize a mapper in tests, compare structured JSON, or approve changes.

## Repeated workflow and adoption cost

The plausible recurring workflow is a dependency-update pull request in a team that owns persisted JSON, shared DTO libraries, or independently deployed producers and consumers:

1. Build the currently released application adapter and the proposed adapter using their real dependency graphs and production mapper construction.
2. Reuse a reviewed corpus of sanitized representative payloads, including edge cases previously found in production or tests.
3. Run both builds and the four producer/consumer combinations in CI before release.
4. Investigate acceptance, output, and observation changes. Correct unintended behavior or record a narrowly scoped, reasoned, expiring expected change.
5. Keep the corpus and accepted decisions for the next dependency or mapper change; preserve the report fingerprint with release evidence.

This workflow is a **product hypothesis inferred from the reported failures**, not a measured user habit. No user interviews, customer commitments, retained users, willingness-to-pay study, or independent adoption measurement were collected for this release. The 50-service comment describes one organization's exposure; it is not 50 customers or installations.

The reason to install is to avoid maintaining process supervision, two-build dependency isolation, cross-version read checks, expiry rules, and private-by-default report plumbing separately. The library/CLI can be a small additional CI step once adapters and corpus exist. It is most useful when the same gate is reused across several upgrades or services, rather than for a one-off comparison of two strings.

Initial setup is not zero-cost. A team must prepare **two build outputs**, provide an adapter implementation for each serializer API version, select DTOs, reproduce mapper/module configuration, define a stable observation projection, sanitize and curate fixtures, and list the actual classpaths in a manifest. SerdeProof does not discover all of this from a repository, export production data safely, or infer meaningful assertions. Package relocation between Jackson 2 and 3 usually requires separate adapter source or builds. Framework-managed configuration may require additional application bootstrap code.

For a small DTO set already covered by good tests, adding ApprovalTests/JsonUnit assertions and explicit old/new build jobs can be simpler than adopting SerdeProof. For source migration, use OpenRewrite; for a Spring Boot default mismatch, first evaluate Boot's supported compatibility settings; for full service behavior and nondeterministic traffic, consider OpenDiffy. SerdeProof is justified only when its packaged execution and review workflow saves more maintenance than its adapters and manifest introduce.

The demo lowers the cost of evaluating the tool, not the cost of modeling a real application. External adoption would be stronger evidence than the current research. Useful follow-up measures are whether a team can connect an existing DTO without rewriting its mapper setup, whether it finds a relevant issue not already covered by tests, and whether the same corpus is reused on a later upgrade. None of those outcomes is claimed here.

## Product decision

Implement a Java library and CLI with these connected capabilities:

- Evaluate the same local payload with baseline and candidate adapters that use the team's actual DTO, mapper configuration, and modules.
- Execute each adapter invocation in a fresh JVM with its own explicit classpath.
- Distinguish payload rejection from timeout, crash, protocol errors, and other infrastructure failures.
- Compare accepted serialized output and user-defined domain observations, preserving missing/null and numeric representation differences.
- Feed each accepted producer's output into both consumers and compare observations, including same-version loss.
- Record narrowly scoped expected changes with a reason and expiry date, while retaining evidence that the difference occurred.
- Produce deterministic reports and fingerprints without exposing raw fixture contents or adapter errors by default.

These capabilities address the observed workflow. They do not prove complete semantic equivalence. A meaningful observation projection and a representative corpus remain the user's responsibility. Hidden state, external side effects, nondeterminism, untested input classes, and application business rules can remain outside the evidence.

## Version and source selection

The demo pins published artifacts verified on the research date:

| Role | Maven coordinate | Evidence |
| --- | --- | --- |
| Baseline demo | `com.fasterxml.jackson.core:jackson-databind:2.22.3` | [Maven Central](https://central.sonatype.com/artifact/com.fasterxml.jackson.core/jackson-databind/2.22.3), [upstream tag](https://github.com/FasterXML/jackson-databind/tree/jackson-databind-2.22.3) |
| Candidate demo | `tools.jackson.core:jackson-databind:3.2.3` | [Maven Central](https://central.sonatype.com/artifact/tools.jackson.core/jackson-databind/3.2.3), [upstream tag](https://github.com/FasterXML/jackson-databind/tree/jackson-databind-3.2.3) |

The [upstream release policy](https://github.com/FasterXML/jackson/wiki/Jackson-Releases) identifies 2.21 and 3.1 as LTS branches and 2.22 and 3.2 as latest release branches. The demo uses current published stable artifacts; it does not prescribe an organization's production upgrade policy. Jackson 3 requires Java 17. Jackson 3 retains the `com.fasterxml.jackson.annotation` annotations package while relocating most other packages; its dependency graph must be resolved explicitly rather than inferred from matching version strings.

## Name check

Before creating the project, searches for `serdeproof` and `serdeproof in:name` returned no GitHub repositories. Exact-name web searches found no existing software product. The release owner also queried the official `search.maven.org` search API successfully and recorded `numFound: 0`. The independent local directory and authenticated account's repository name were checked before creation.

These checks establish the observed state of those searches at creation time. They are **not trademark clearance**, a domain-name reservation, or a guarantee against an unindexed or later name collision. “Proof” in the product name is not a semantic guarantee.
