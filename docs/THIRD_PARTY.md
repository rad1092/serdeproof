# Third-party components

SerdeProof's own code is licensed under [Apache License 2.0](../LICENSE). The license file is the complete standard text downloaded from [the Apache Software Foundation](https://www.apache.org/licenses/LICENSE-2.0.txt) on 2026-10-08 (11,358 bytes; SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`). Third-party components retain their own licenses and notices.

## Runtime boundaries and pinned demos

| Module / role | Component | Pin | Declared upstream license |
| --- | --- | --- | --- |
| `serdeproof-api` | Java standard library only; no external serializer dependency | Java 17 minimum | Supplied by the user's JDK distribution |
| `serdeproof-core` and CLI runtime | `com.fasterxml.jackson.core:jackson-databind` | `2.22.3` | Apache-2.0 |
| `demo-jackson2` | `com.fasterxml.jackson.core:jackson-databind` | `2.22.3` | Apache-2.0 |
| `demo-jackson3` | `tools.jackson.core:jackson-databind` | `3.2.3` | Apache-2.0 |
| Jackson 2 transitive runtime | `com.fasterxml.jackson.core:jackson-core` | `2.22.3` | Apache-2.0; includes separately licensed embedded code below |
| Jackson 3 transitive runtime | `tools.jackson.core:jackson-core` | `3.2.3` | Apache-2.0; includes separately licensed embedded code below |
| Shared annotations dependency | `com.fasterxml.jackson.core:jackson-annotations` | `2.22` | Apache-2.0 |
| Test suite | `org.junit.jupiter:junit-jupiter` | `6.1.3` | EPL-2.0 |

Jackson databind's published POMs declare Apache-2.0: [Jackson 2 POM and metadata](https://central.sonatype.com/artifact/com.fasterxml.jackson.core/jackson-databind/2.22.3), [Jackson 3 POM and metadata](https://central.sonatype.com/artifact/tools.jackson.core/jackson-databind/3.2.3). The transitive runtime graph includes Jackson core and Jackson annotations. Jackson 3 retains the `com.fasterxml.jackson.annotation` package; annotations must not be assigned an invented `tools.jackson` coordinate or a version inferred from the databind version.

The runner's JSON parser belongs to `serdeproof-core`'s graph. It is independent of the application dependencies loaded in each adapter process. Test frameworks and Maven build plugins are build-time components; they should not be presented as API runtime dependencies.

The upstream [2.22.3 BOM](https://github.com/FasterXML/jackson-bom/blob/jackson-bom-2.22.3/pom.xml) and [3.2.3 BOM](https://github.com/FasterXML/jackson-bom/blob/jackson-bom-3.2.3/pom.xml) identify the core and annotations versions above. [JUnit's published POM](https://central.sonatype.com/artifact/org.junit.jupiter/junit-jupiter/6.1.3) declares Eclipse Public License 2.0. The release dependency inventory covers resolved reactor compile/runtime/test dependencies, including test transitives. It excludes build plugins and the JDK. This table describes the principal module boundaries, not every build-time artifact.

## Code embedded in Jackson core

The Jackson core artifacts include FastDoubleParser and Schubfach code. The embedded notices identify MIT, Boost Software License 1.0, and BSD 2-Clause material, so the full distribution must not be described as containing only Apache-2.0 code. Upstream resource names are `META-INF/jackson-core-NOTICE`, `META-INF/jackson-core-LICENSE`, `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE`, and `META-INF/Schubfach-LICENSE`.

[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) reproduces the relevant notices and embedded license texts from the pinned Jackson source tags. The build must also retain those resources when producing dependency-containing JARs. Source-tag notices were inspected for both pinned core versions; the FastDoubleParser and Schubfach license resources were identical at those tags.

## Distribution and verification

Use the reactor POMs and the generated dependency inventory from the release verification as the authority for exact resolved versions and transitive components. Review licenses and embedded `META-INF/*LICENSE*` / `META-INF/*NOTICE*` resources when packaging dependencies into an executable or demo JAR. A summary table is not a substitute for retaining required upstream notices.

The project sources packages from Maven Central and upstream repositories. Pinning a version and checking its declared license do not establish that it has no vulnerabilities. Security scan results apply only to the recorded coordinates, database state, and scan date. Historical regression examples must be labeled as historical; old vulnerable versions should not be mistaken for recommended production dependencies.

No claim of signature verification, signed SerdeProof releases, notarization, or Maven Central publication is made by this document. Consult the release's recorded verification evidence for checks actually executed and their outcomes.
