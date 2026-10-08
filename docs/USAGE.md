# Usage

## Build, install, run

Requires JDK 17+; the wrapper pins Maven 3.9.11. Development smoke and audit scripts require Python 3.10+. The shipped product itself requires only a JVM. Run build commands from a source checkout or the release ZIP's `source/` directory.

```sh
./mvnw -B -ntp clean install
python3 scripts/smoke.py
java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar --help
```

Windows PowerShell: `.\mvnw.cmd -B -ntp clean install`, then `python scripts/smoke.py`. The smoke uses installed artifacts in `~/.m2/repository`, or `.local-repository` when that project-local directory exists. Set `SERDEPROOF_MAVEN_REPO` when you use another Maven local repository.

Maven adapter dependency (installed locally or published to your own artifact repository):

```xml
<dependency>
  <groupId>io.github.rad1092.serdeproof</groupId>
  <artifactId>serdeproof-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

Use `serdeproof-core` instead for a library caller. The core transitively includes the API and its report parser; keep it out of adapter dependency graphs where possible. Gradle Kotlin DSL can consume the same installed artifacts:

```kotlin
repositories { mavenLocal(); mavenCentral() }
dependencies { testImplementation("io.github.rad1092.serdeproof:serdeproof-api:0.1.0") }
```

The Maven reactor and installed API example are exercised in CI. The Gradle declaration is a dependency-consumption example, not a separately tested Gradle plugin. No coordinates are published to Maven Central by this release.

## Adapter contract

Implement `SerializerAdapter` in a public class with a public no-argument constructor. The method receives a fixture `type`, raw payload bytes and configuration bytes (`{}` by default). It returns two **UTF-8 JSON documents without a byte-order mark** in an `AdapterResult`: your mapper's serialized output and an application-authored observation of the DTO. The framework never reconstructs your DTO using its own mapper.

Catch only payload/data exceptions as `RejectedInputException`. Leave missing classes, bad mapper definitions, programming failures and unexpected exceptions uncaught so the run is incomplete. The runner hides their messages. Investigate such failures by running your adapter's tests locally; do not expose production payloads in CI logs.

For the same fixture type, both adapters' observations must share a stable vocabulary. Express date instants in an agreed representation, enum identity via `name()`, monetary amounts without binary floating-point conversion, and any relevant presence flags. An observation can contain information your serializer drops: the same-version matrix then detects that loss. Do not canonicalize away distinctions your business cares about.

Use your application's existing mapper factory and module registration. Rebuild the adapter with each application version's dependency graph. Package a self-contained JAR or list every classpath JAR/directory explicitly. Adapters execute sequentially, in a fresh JVM per invocation; this costs up to six JVM launches per fixture. Static state is not shared between fixtures.

The runner launches each adapter through a temporary manifest-only JAR whose `Class-Path` contains the supplied entries as encoded file URLs. Normal class and resource loading uses those entries, including paths with spaces or emoji. Inside an adapter, `java.class.path` names the temporary launcher, not the full dependency list; code that scans that property directly may need adaptation. The runner removes the launcher during cleanup.

## Manifest

```json
{
  "baseline": {
    "adapterClass": "example.OrderAdapter",
    "classpath": ["build/old-adapter.jar"],
    "configuration": "mapper-old.json",
    "jvmArgs": ["-Xmx256m"]
  },
  "candidate": {
    "adapterClass": "example.OrderAdapter",
    "classpath": ["build/new-adapter.jar"],
    "configuration": "mapper-new.json"
  },
  "fixtures": [
    {"id": "queued-order", "type": "order", "file": "corpus/order.json",
     "hints": {"/createdAt": "DATE", "/status": "ENUM"}}
  ],
  "limits": {"maxBytes": 1048576, "timeoutMillis": 10000,
             "maxFixtures": 1000, "maxDifferences": 10000,
             "maxCorpusBytes": 268435456}
}
```

Paths inside the manifest resolve relative to that manifest, even when invoked elsewhere. CLI paths such as `--manifest`, `--json` and `--junit` resolve from the current working directory. `java` can optionally select a JVM executable per adapter; without it, the current JVM is used. `jvmArgs` is an argument array, not a shell string. Do not put credentials there. Wildcard classpaths are rejected. The runner includes the adapter API through your supplied classpath; the demo JARs bundle it.

IDs contain 1–128 ASCII letters/numbers plus `.`, `_`, `-`, starting with a letter or digit; files can have Unicode names. Unknown manifest fields, duplicate IDs, duplicate JSON keys, invalid limits and ambiguous rules fail closed. Fixtures are raw bytes and may deliberately contain invalid JSON or trailing tokens, so the tested serializer decides whether to accept them. Adapter output and observation, and the manifest, must be strict JSON encoded as UTF-8 without a byte-order mark. See the [report reference](REPORT-FORMAT.md) for parser and report limits.

`hints` are exact RFC 6901 pointers into observations, labeling DATE/ENUM differences. They do not change equality or parse dates. For keys containing `/` use `~1`, and for `~` use `~0`; the empty pointer identifies the whole document. No JSONPath globbing is used.

## Expected changes

Add `rules` to the manifest only after reviewing a report:

```json
{"id":"accept-extra-field", "fixture":"unknown-field", "kind":"ACCEPTANCE",
 "direction":"baseline-to-candidate", "pointer":"",
 "reason":"Consumer now intentionally ignores producer extension fields; tracked in migration review.",
 "expires":"2027-03-31"}
```

A rule matches exactly one fixture/kind/direction/pointer scope. There are no wildcard rules. `reason` and `expires` are mandatory. Expiry is evaluated against the CLI's `--as-of` date (UTC today by default). Expired and unused rules fail with exit 2; a reviewed change that disappears needs its rule removed. Rules never suppress infrastructure errors.

Initial comparison direction is `baseline-to-candidate`. Kind is `ACCEPTANCE`, `OUTPUT_...` or `OBSERVATION_...`; the suffix is `MISSING`, `NULL`, `TYPE`, `VALUE`, `NUMERIC_VALUE`, `NUMERIC_LEXICAL`, or observation hints `DATE`/`ENUM`. Matrix directions are `baseline-to-baseline`, `baseline-to-candidate`, `candidate-to-baseline`, `candidate-to-candidate`; findings use `ROUNDTRIP_ACCEPTANCE` or `ROUNDTRIP_...`. Copy the exact kind and pointer from a locally generated `--details` report. [jackson-reviewed.json](../examples/jackson-reviewed.json) is an executable example.

## Reports and release gate

```sh
java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar run \
  --manifest examples/compatible.json --json reports/compatibility.json \
  --junit reports/compatibility.xml --as-of 2026-10-08
```

Report destinations must be distinct from each other and the manifest. Existing files are protected unless `--force` is explicit. Select output paths separate from your fixtures; `--force` replaces the selected paths. On partial filesystem failure, one report can be written before the second fails, and the CLI exits 2.

| Exit | Meaning |
|---|---|
| 0 | No unexpected findings. Inspect `untested` cases and corpus coverage. |
| 1 | Unexpected acceptance, output, observation or roundtrip difference. |
| 2 | Invalid/incomplete experiment, invalid/expired/unused rule, limits, or infrastructure error. |
| 130 | Java interruption handled; process cleanup requested. OS signal exit values can differ. |

Always fail your CI gate on any nonzero exit. A malformed adapter response, timeout, crash, output flood or missing classpath is never reported as “compatible.” A fixture initially rejected by both adapters has no semantic result and appears as untested/skipped. Expected differences remain in JSON so reviewers can see what was waived.

The report records the tool/schema version, evaluation date, OS/JVM environment, limits, fixture content hashes, classpath fingerprints, mapper configuration hashes and JVM argument fingerprints. Environment-dependent behavior and external resources read by adapters are not automatically captured. Store sanitized source fixtures and the manifest privately with your build revision to reproduce a result; hashes alone cannot reconstruct them.

Default reports conceal IDs and pointers. `--details` reveals fixture/rule identities, precise pointers and rule reasons; it still excludes values, fixture paths, adapter stdout/stderr and error text. See [SECURITY](../SECURITY.md) before sharing reports.
