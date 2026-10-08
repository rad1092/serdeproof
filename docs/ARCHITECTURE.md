# Architecture and design tradeoffs

SerdeProof is a local Java library and CLI for observing serializer migration behavior. The [specification](SPEC.md) defines the contract. A successful run means the configured corpus produced no unexpected findings; it does not prove equivalence for all inputs or establish the correctness of either serializer.

## Modules and execution boundary

| Module | Responsibility |
| --- | --- |
| `serdeproof-api` | Dependency-free adapter interface, result/rejection types, and the bounded binary process entry point. |
| `serdeproof-core` | Manifest interpretation, JVM supervision, JSON comparisons, compatibility matrix, expected-change rules, fingerprints, and reports. |
| `serdeproof-cli` | Command-line entry point and documented exit status. |
| `demo-jackson2` | An application-style DTO adapter with pinned Jackson 2 dependencies. |
| `demo-jackson3` | A corresponding adapter with pinned Jackson 3 dependencies. |
| `serdeproof-dist` | A release ZIP containing runnable JARs, library artifacts, examples, source, and documentation. |

```text
local manifest + local corpus
            |
      core / CLI runner
        |           |
   fresh JVM A   fresh JVM B
   baseline      candidate
   DTO + mapper  DTO + mapper
        |           |
  JSON output + domain observation
            |
  comparison + producer/consumer re-evaluation
            |
  deterministic JSON and JUnit XML
```

The runner must not put baseline and candidate application dependencies on its own classpath. Each adapter classpath is an explicit list in the manifest. File paths resolve relative to that manifest, and process arguments are passed as an argument list without a shell, wildcard expansion, or command-string splitting. This makes spaces and Unicode paths ordinary paths rather than shell syntax.

Each invocation uses `java -jar` with a temporary manifest-only launcher. Its manifest `Class-Path` lists the supplied entries as encoded file URLs, supporting spaces and supplementary Unicode characters in paths without a long command-line classpath. Ordinary class/resource loading follows those entries. However, `java.class.path` inside the adapter points to the launcher; application code that interprets that property as a list of its dependencies may need adaptation. Cleanup removes the launcher, and the runner does not rewrite or copy the adapter JARs.

## Why Java 17 and Maven

Java lets adapters use the application's real DTO classes, annotations, serializer modules, and mapper setup without reimplementing their behavior in another language. Java 17 matches [Jackson 3's minimum JDK](https://github.com/FasterXML/jackson-databind#jdk) and provides the process APIs needed for supervision. The release verification targets Java 17 and 21 on macOS, Linux, and Windows; a configured CI matrix is not evidence that a particular commit passed it.

Maven provides a conventional reactor for the API, runner, CLI, and two independently resolved demos. Explicit dependency/plugin versions, `verify`, and local `install` are easy to reproduce in CI and downstream examples. Gradle would also support separate configurations, rich dependency constraints, and publication. It was not selected because maintaining a second build would add surface area without changing the runtime isolation or migration evidence. Consumers can use the installed library artifacts from either Maven or Gradle.

The project distributes source, JARs, and GitHub release assets. A local Maven installation is distinct from publication to Maven Central. Do not infer Central publication, artifact signing, or platform notarization from successful packaging.

## Why processes instead of classloaders

Separate classloaders can avoid some dependency collisions and reduce startup cost. They still share the host JVM, process-wide properties, native libraries, threads, shutdown behavior, and other global resources. They cannot reliably contain an adapter calling `System.exit`, hanging, or contaminating process state.

SerdeProof starts one new JVM per evaluation. Baseline and candidate can therefore use different dependency graphs and even different configured Java executables. An ordinary adapter crash or timeout can be classified without bringing down the runner. Fresh invocations avoid carrying static mapper caches or mutable application state from one fixture to another.

The cost is deliberate: an accepted fixture can require **six JVM invocations**—two initial evaluations plus four producer/consumer checks. Startup, class loading, and repeated initialization can dominate small fixtures. This is a bounded migration test harness, not a throughput benchmark or a low-latency service. Long-lived workers would need an explicit state-reset and failure-recovery contract before they could replace this behavior.

Process isolation is **not a security sandbox**. Adapters run with the user's normal filesystem, network, and process privileges. They may load native code or start descendants. Cleanup of observable descendants is best effort; hostile code can detach or otherwise escape ordinary process supervision. Only run trusted local adapter code. Use an external OS/container boundary when a stronger trust boundary is required.

## Adapter contract and protocol

```java
AdapterResult evaluate(String type, byte[] input, byte[] configuration)
```

The adapter owns DTO selection, mapping, configuration interpretation, and the observation projection. `output` and `observation` in `AdapterResult` must each contain a strict UTF-8 JSON document without a byte-order mark. `RejectedInputException` means an expected payload rejection; unexpected exceptions belong to infrastructure diagnostics rather than the acceptance result.

The observation is an application-defined projection of the decoded value. For example, a date observation can express an epoch value and an enum observation can express the resolved member name. The runner does not infer business meaning from arbitrary strings. Optional fixture hints label observation pointers as `DATE` or `ENUM`; they label differences rather than automatically normalizing them.

The API exchanges byte arrays through a bounded binary protocol. It has no serializer dependency, so a Jackson version is not required merely to implement an adapter or decode its process envelope. A JSON wire envelope implemented by Jackson would make every adapter inherit that wire library/version and could reintroduce the very dependency conflict under test. The tradeoff is a protocol that is less convenient to inspect manually and must reject malformed or oversized frames explicitly.

The core uses Jackson 2.22.3 to understand JSON for manifests, observations, output comparison, and reporting. That parsing belongs to the runner dependency graph, not the adapter API's binary envelope. Pinning the runner's JSON implementation and testing numeric precision is necessary because its interpretation is part of the evidence pipeline.

`AdapterMain` loads a public no-argument adapter constructor and handles exactly one request. Adapter standard output is redirected to standard error so normal application logging cannot masquerade as protocol data. Neither stream is placed in public reports. Invalid frames, invalid returned JSON, process crashes, limits, and deadlines produce infrastructure failures; they must not silently become compatible results.

## Comparisons and the four-way matrix

First, both adapters evaluate the original fixture:

- One accepts and the other rejects: acceptance change.
- Both accept: compare serialized output and domain observations.
- Both reject: record that semantic compatibility was not tested for that fixture. Agreement on rejection does not establish equivalent decoded values.
- An infrastructure failure prevents a trustworthy compatibility conclusion for the affected evaluation.

For each accepted initial producer, send its output to both consumer adapters:

| Producer | Consumer | Question asked on this fixture |
| --- | --- | --- |
| Baseline | Baseline | Does the old implementation preserve its observed value through its own emitted representation? |
| Baseline | Candidate | Can the new implementation consume old output while preserving the producer's observation? |
| Candidate | Baseline | Can the old implementation consume new output while preserving the producer's observation? |
| Candidate | Candidate | Does the new implementation preserve its observed value through its own emitted representation? |

A consumer's observation is compared with the corresponding producer's observation. This is stronger than merely checking that deserialization returns successfully. The matrix includes same-version loss that a baseline-versus-candidate comparison could otherwise conceal. Rejected producers do not generate usable output for their matrix rows.

The matrix covers two supplied implementations and the representations produced by this corpus. It does not cover all historical producers, all stored records, independent services, non-JSON wire formats, or all possible valid values. Matching observations only covers the fields and semantics that the adapter exposes. If both implementations share a defect, or an observation omits a changed field, the matrix may not reveal it.

JSON differences use [RFC 6901 pointers](https://www.rfc-editor.org/rfc/rfc6901), including escaping for `~` and `/`. Missing fields and explicit JSON null are separate cases. Numeric lexical changes are distinguished from numeric value changes: `1`, `1.0`, and `1e0` can carry equal numeric values while having different representations. Object member order is not a JSON value difference; array order is significant. Date and enum labels require explicit hints, not guesses based on a value's appearance.

## Expected changes and deterministic reports

An expected-change rule identifies an exact fixture, difference kind, direction, and pointer. It includes its own ID, a reason, and an ISO expiry date. Broad suppression can hide new regressions, so scope and expiry are part of the reviewable decision. Evaluation uses a recorded date; re-running with a different date may legitimately change whether a rule has expired. An expiry date is valid through that date. Expired and unused rules fail the run with a rule error; they are not silently retained forever.

JSON and JUnit XML reports are sorted and exclude wall-clock timings, random IDs, and absolute local paths. Reproducibility metadata includes content fingerprints for fixtures, configuration, and classpath entries, plus tool/JVM/OS information and configured limits. Different JVMs or operating systems can produce intentionally different environment metadata; deterministic does not mean every cross-platform report has identical bytes.

Fingerprints identify the inputs the runner observed. They are not signatures, provenance attestations, or a complete inventory of everything trusted code could access. An adapter can read an environment variable, network service, clock, or file outside its manifest. Applications should keep adapters deterministic and include all relevant configuration in the tracked inputs.

## Privacy and limits

Default public reports hash fixture IDs, type identifiers, rule IDs, and pointers. They omit fixture filenames, raw JSON values, raw exception text, and process streams. This matters because a JSON key can itself contain an account identifier or other sensitive dynamic data. Opt-in detailed reports reveal those identifiers, pointers, and rule reasons, but still do not include payload values or adapter exception text.

Hashes reveal equality and can be guessed for small or predictable input spaces. Metadata, hint choices, counts, and opt-in text can also disclose information. Inspect a report before sharing it; these defaults reduce accidental disclosure rather than guarantee anonymization. Synthetic fixtures are the only fixtures intended for the public demo repository.

Defaults bound fixture/configuration/individual adapter fields to 1 MiB, each invocation to 10 seconds, a corpus to 1,000 fixtures and 256 MiB of fixture data, and a report to 10,000 differences. A manifest is limited to 1 MiB. JSON evidence parsing rejects duplicate fields and trailing content, and limits nesting to 128 containers, numeric tokens to 1,000 characters, strings to 1 MiB, names to 64 KiB, and total nodes to 100,000 per document. Pointer and metadata budgets add bounds to comparison output. Input and stream bounds are enforced before unbounded buffering. Exceeding a limit fails closed rather than emitting a clean verdict. These bounds are not OS-enforced memory or CPU quotas. Cancellation terminates active children; descendant cleanup retains the best-effort limitation described above.

Exit codes distinguish a clean configured run (`0`), unexpected acceptance/output/observation/roundtrip findings (`1`), configuration/infrastructure/rule errors (`2`), and interruption (`130`). Exit `0` is a workflow result for the tested corpus, not a semantic certificate.
