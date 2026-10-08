# SerdeProof

Run your existing Java DTOs, mapper configuration and payload corpus against two serializer builds before shipping an upgrade.

SerdeProof is a Java 17 library and CLI. Each adapter runs in its own JVM and dependency graph. It records input acceptance, precise JSON output changes, domain observations and the four baseline/candidate producer→consumer combinations. Use it as a small CI release gate alongside your existing tests.

**Passing means no unexpected differences were observed in this corpus. It does not prove business equivalence. Adapters are trusted local code, with your privileges; this is not a sandbox.**

## Try the maintained example

Install a JDK 17 or newer and run these commands from a source checkout (or the release ZIP's `source/` directory). The Maven wrapper downloads pinned Maven 3.9.11 from Maven Central.

```sh
./mvnw -B -ntp clean install
python3 scripts/smoke.py
java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar run \
  --manifest examples/demo.json --json reports/demo.json --junit reports/demo.xml \
  --as-of 2026-10-08 --details
```

On Windows PowerShell use `.\mvnw.cmd` and `python`; the `java` command is identical when written on one line. The demo intentionally finds differences, so the last command exits **1**. Exit **2** means an incomplete or invalid experiment; it must not be treated as a compatibility finding. For another run, choose fresh report filenames or add `--force` to replace the previous reports.

The [GitHub release](https://github.com/rad1092/serdeproof/releases) distributes a ZIP and standalone executable JARs. Unzip the ZIP and use its `lib/serdeproof-cli-0.1.0.jar` with `examples/release-demo.json`. Release assets are unsigned and not notarized. Coordinates are installed locally by `mvn install`; they are **not published to Maven Central**.

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
Report report = new MigrationLab().run(manifest, LocalDate.of(2026, 10, 8), false);
if (report.exitCode() != 0) throw new AssertionError("Serializer gate failed");
```

Core coordinates are `io.github.rad1092.serdeproof:serdeproof-core:0.1.0`; the adapter needs only `serdeproof-api:0.1.0`. Maven and Gradle dependency declarations, explicit manifest fields and expected-change examples are in [USAGE](docs/USAGE.md). JUnit XML can be uploaded by any CI test reporter. No Maven or Gradle plugin is required.

## Scope and alternatives

Use your existing JUnit/JsonUnit or ApprovalTests tests when they already cover both builds well. OpenRewrite migrates source, Spring Boot offers Jackson 2 compatible defaults, and schema registries check schema evolution. SerdeProof saves the harness work of running separately built serializers with bounded subprocesses, a shared corpus, a four-way matrix, reviewed exceptions and reproducibility evidence. It does not replace these tools. See the [source-backed comparison and adoption costs](docs/RESEARCH.md).

Start with sanitized payloads that caused incidents or reflect real queues and storage. The maintained examples are synthetic. A representative corpus and faithful observations require engineering judgment; fresh JVMs trade speed for dependency isolation. See [limitations and trust boundaries](SECURITY.md).

- [한국어 빠른 시작](docs/QUICKSTART.ko.md)
- [Usage and manifest reference](docs/USAGE.md)
- [Architecture decisions](docs/ARCHITECTURE.md)
- [Reproduction and validation evidence](docs/VALIDATION.md)
- [Changelog](CHANGELOG.md) · [Apache-2.0 license](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)
