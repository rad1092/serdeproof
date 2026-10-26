# SerdeProof

See how your JSON changes before moving to Jackson 3 or Spring Boot 4.

Spring Boot 4.0 and Spring Framework 7.0 make Jackson 3 the default. Jackson 3
writes dates as ISO-8601 strings instead of timestamps and sorts properties
alphabetically ([Spring blog](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/)).
SerdeProof runs your own DTOs, mapper settings and sample payloads on both
versions, each in its own JVM, and reports:

- which payloads one version accepts and the other rejects,
- which values in the JSON output changed, such as a date that became a string,
- whether JSON written by one version can still be read by the other.

It compares JSON by structure and value, so a failing snapshot test caused only
by the new property order shows up as no difference. Use it as a CI gate while
you upgrade: approve the changes you expect, with a reason and an expiry date,
and the build fails only on new ones.

```sh
java -jar lib/serdeproof-cli-0.1.1.jar run --manifest serdeproof.json \
  --json reports/run.json --junit reports/run.xml --markdown reports/summary.md
```

`--markdown` writes a short summary for a pull request or a GitHub Actions job
summary (`--markdown "$GITHUB_STEP_SUMMARY" --force`).

## Try the release without building

Download `serdeproof-0.1.1.zip` and `SHA256SUMS` from
[Releases](https://github.com/rad1092/serdeproof/releases/tag/v0.1.1), verify the
archive's SHA-256, and extract the whole archive. With Java 17+ installed, open a
terminal **inside the extracted `serdeproof-0.1.1` folder** and run:

```sh
java -jar lib/serdeproof-cli-0.1.1.jar run --manifest examples/release-demo.json --json reports/first.json --junit reports/first.xml --as-of 2026-10-08
```

The command intentionally exits **1**: the example Jackson 2 → 3 payloads have
differences to review. Add `--markdown reports/first.md` for a readable summary,
or open `reports/first.json`: its `summary` should show 17 fixtures processed,
12 unexpected differences, 5 untested fixtures and 0 infrastructure errors. Each fixture includes four producer/consumer checks.
Exit **2** means the experiment could not complete, not an ordinary migration
finding. The same command works on PowerShell as one line.

The demo result was reproduced on macOS arm64 with Java 17 on 2026-10-12.
Use **0.1.1 or later** for the process-ownership fix described in the changelog.
No Maven install or hosted service is needed for this release demo.
Keep all `lib/` JARs together; copying only the CLI JAR does not supply the demo
adapters. Reports default to hashed identities; `--details` reveals fixture IDs,
paths within JSON and rule metadata. Neither mode writes fixture payload values.
Choose new report filenames for another run, or explicitly use `--force` to
replace reports you have reviewed. See [the examples guide](examples/README.md)
to interpret acceptance, roundtrip loss and reviewed exceptions.

## Build and adapt the maintained example

Install a JDK 17 or newer and run these commands from a source checkout (or the release ZIP's `source/` directory). The Maven wrapper downloads pinned Maven 3.9.11 from Maven Central.

```sh
./mvnw -B -ntp clean install
python3 scripts/smoke.py
java -jar serdeproof-cli/target/serdeproof-cli-0.1.1.jar run \
  --manifest examples/demo.json --json reports/demo.json --junit reports/demo.xml \
  --as-of 2026-10-08 --details
```

On Windows PowerShell use `.\mvnw.cmd` and `python`; the `java` command is identical when written on one line. The demo intentionally finds differences, so the last command exits **1**. Exit **2** means an incomplete or invalid experiment; it must not be treated as a compatibility finding. For another run, choose fresh report filenames or add `--force` to replace the previous reports.

The [GitHub release](https://github.com/rad1092/serdeproof/releases) distributes a ZIP and standalone executable JARs. Unzip the ZIP and use its `lib/serdeproof-cli-0.1.1.jar` with `examples/release-demo.json`. Release assets are unsigned and not notarized. Coordinates are installed locally by `mvn install`; they are **not published to Maven Central**.

## Bring your DTO and mapper

Implement the dependency-free [`SerializerAdapter`](docs/USAGE.md#adapter-contract) in a tiny test-support module compiled twice, once for each application's dependency version. This Jackson 2 sketch keeps mapper definition failures separate from payload rejection:

```java
public AdapterResult evaluate(String type, byte[] input, byte[] configuration)
        throws Exception {
    Order dto;
    try {
        dto = serviceMapper.readValue(input, Order.class);
    } catch (InvalidDefinitionException mapperFailure) {
        throw mapperFailure;
    } catch (JsonProcessingException invalidPayload) {
        throw new RejectedInputException();
    }
    return new AdapterResult(serviceMapper.writeValueAsBytes(dto),
                             observeOrder(dto));
}
```

`output` is what your real serializer writes. `observation` is JSON describing the domain facts you care about—for example the instant, enum identity and exact amount. Keep observations stable across versions and include relevant fields. Unexpected exceptions are infrastructure failures, not accepted rejections. The complete [custom adapter example](examples/custom-adapter/) includes the DTO, a custom mapper module, the adapter, and a library caller. The install smoke compiles and executes it against installed artifacts.

Configure explicit classpath entries for each version in the [manifest](examples/demo.json). Paths are relative to that manifest and may contain spaces or Unicode. Do not put both versions of Jackson in one adapter classpath. No shell command, server, database, hosted service or credentials are required.

## What the report answers

| Evidence | Decision it supports |
|---|---|
| Input accepted by one version and rejected by another | Can the new reader consume stored/queued payloads? |
| JSON pointer and missing/null/type/numeric/value changes | Does the wire format change? |
| Observation changes, optional DATE/ENUM hints | Did the adapter observe a domain change? |
| Baseline→candidate and candidate→baseline | Can mixed-version readers consume each producer's output? |
| Baseline→baseline and candidate→candidate | Does a serializer's own roundtrip lose an observed fact? |
| Scoped expected changes with reason and expiry | Which changes were reviewed, and when must they be reviewed again? |

The matrix checks acceptance and the producer's observation against the consumer's observation. It does not infer schema compatibility or recover fields that an adapter omitted from its observation. Two rejections are explicitly `untested`, not a proof of compatibility.

Reports are deterministic for the same artifacts, fixtures, environment and `--as-of` date. They include content fingerprints and limits. The default report hashes fixture IDs, JSON pointers and rule identities; `--details` exposes these and rule reasons. **Neither mode prints payload values, mapper errors or stderr.** Hashes still reveal equality and may be guessed for low-entropy inputs. Review reports before publishing.

## Integrate with an existing build

After installing or publishing the artifacts to your own repository, the library entry point is:

```java
Report report = new MigrationLab().run(manifest, LocalDate.of(2026, 10, 8), false, true);
if (report.exitCode() != 0) throw new AssertionError("Serializer gate failed");
```

For a CI gate that requires every fixture to be compared, pass the fourth
`failOnUntested` argument as `true`, as above, or add `--fail-on-untested` to the
CLI. Any nonzero `summary.untestedFixtures` then produces exit **2**, a
`COVERAGE/UNTESTED_FIXTURES` JSON error and a JUnit error. The default keeps
rejection-testing workflows valid: two rejected inputs remain skipped, so exit
0 alone does not establish semantic coverage. The release demo intentionally
contains five such cases; inspect or separate them before using a strict gate.

Report paths cannot overlap the manifest, fixture/configuration files, Java
executables or declared classpath entries/trees, even with `--force`. Input aliases
are checked before execution and again before publication. Keep reports outside
source/classpath trees; ordinary concurrent edits are checked, but publication
is not a security boundary against hostile filesystem races.

Core coordinates are `io.github.rad1092.serdeproof:serdeproof-core:0.1.1`; the adapter needs only `serdeproof-api:0.1.1`. Maven and Gradle dependency declarations, explicit manifest fields and expected-change examples are in [USAGE](docs/USAGE.md). JUnit XML can be uploaded by any CI test reporter. No Maven or Gradle plugin is required.

## Scope and alternatives

Use your existing JUnit/JsonUnit or ApprovalTests tests when they already cover both builds well. OpenRewrite migrates source, Spring Boot offers Jackson 2 compatible defaults, and schema registries check schema evolution. SerdeProof saves the harness work of running separately built serializers with bounded subprocesses, a shared corpus, a four-way matrix, reviewed exceptions and reproducibility evidence. It does not replace these tools. See the [source-backed comparison and adoption costs](docs/RESEARCH.md).

Start with sanitized payloads that caused incidents or reflect real queues and storage. The maintained examples are synthetic. A representative corpus and faithful observations require engineering judgment; fresh JVMs trade speed for dependency isolation. See [limitations and trust boundaries](SECURITY.md).

- [한국어 빠른 시작](docs/QUICKSTART.ko.md)
- [Usage and manifest reference](docs/USAGE.md)
- [Architecture decisions](docs/ARCHITECTURE.md)
- [Reproduction and validation evidence](docs/VALIDATION.md)
- [Changelog](CHANGELOG.md) · [Apache-2.0 license](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## Limitations

- A pass means your sample payloads showed no unexpected difference. Payloads you
  didn't include are not covered, and it doesn't prove the business meaning is the same.
- Adapters are your own code and run with your permissions. This is not a sandbox.
- File names with non-ASCII characters need a UTF-8 locale. In a container, run with
  `LANG=C.UTF-8`.
- Not on Maven Central yet. Build from source or use the release ZIP.
