# SerdeProof 0.1

## Objective

A Java library and CLI for teams upgrading a serializer beneath existing DTOs. Local fixture corpora execute against user-supplied baseline and candidate adapters in separate JVM processes. The report distinguishes acceptance, serialized JSON shape, domain observation and producer-consumer roundtrip changes. Observations are user-defined evidence, never a proof of complete semantic equivalence.

## Architecture and contract

- Maven Java 17 reactor: `serdeproof-api`, `serdeproof-core`, `serdeproof-cli`, `demo-jackson2`, `demo-jackson3`.
- API package `io.github.rad1092.serdeproof.api` is dependency-free.
- `SerializerAdapter.evaluate(String type, byte[] input, byte[] configuration)` returns `AdapterResult(byte[] output, byte[] observation)`; both response fields must be JSON documents. Throw `RejectedInputException` only for expected payload rejection. Other errors are infrastructure failures.
- `AdapterMain` loads the adapter via public no-arg constructor, performs exactly one request per fresh JVM, and speaks a bounded binary protocol. It redirects adapter stdout to stderr so application logs cannot masquerade as protocol. Neither stream is published.
- Core compares baseline and candidate acceptance and JSON output, and their observation JSON. For each accepted producer, both consumer adapters read the producer output; compare the producer observation with the consumer observation. Same-version loss is visible too.
- RFC 6901 JSON pointers identify differences. Numeric lexical differences are distinguished from numeric value changes; missing, explicit null, type, value, date-tagged and enum-tagged observations are distinct evidence. Adapter observations use ordinary JSON; optional corpus `hints` map pointers to DATE/ENUM to label domain comparisons without guessing semantics.
- User adapters are trusted code with normal user privileges. Process isolation prevents dependency collisions and bounds ordinary failures, not a security sandbox. Descendant cleanup is best effort; hostile code can escape OS process supervision.

## Configuration

Run manifest: `baseline` and `candidate` each specify `adapterClass`, `classpath` (array of relative paths), optional `java` executable, optional `jvmArgs` array, optional `configuration` file. `fixtures` array entries have safe explicit `id`, `type`, `file`, optional `hints`. All paths resolve relative to manifest, not current directory. No shell splitting or wildcard classpaths. Optional `rules` have exact `id`, `fixture`, `kind`, `direction`, `pointer`, required `reason`, and ISO `expires`.

Defaults: 1 MiB fixture/config/individual adapter field, 10 seconds per invocation, 1,000 fixtures, 10,000 differences per report (fail closed when exhausted). Input limits and deadlines also cover process streams. Cancellation terminates active children.

## Reports and privacy

Deterministic sorted JSON and JUnit XML; no wall-clock durations, random IDs or absolute paths. Record SHA-256 corpus/config/classpath content fingerprints, OS/JVM/version, limits and evaluation date for rule expiry. Default fixture IDs and JSON pointers are hashed; opt-in details reveals IDs/pointers and rule reasons but never values or adapter exception text. Hashes leak equality and can be guessed for low-entropy inputs; inspect before publishing.

Exit: 0 no unexpected differences, 1 unexpected semantic/acceptance/roundtrip findings, 2 configuration/infrastructure/rule errors, 130 interrupted. Both rejected is recorded as untested semantics, not successful compatibility.

## Commands and layout

`mvn -B verify`, `mvn -B install`, `java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar --help`.
Sources follow Maven layout; maintained tests cover parser precision, rule scope/expiry, process limits/timeout/crash/protocol/cancellation and real Jackson defaults. Examples demonstrate installed library and custom DTO adapter usage. Docs include Korean quickstart, research, security, limitations, reproduction and release evidence.

## Verification and boundaries

CI runs Maven tests and installed-artifact smoke on macOS/Linux/Windows with Java 17 and 21. Locally execute actual release CLI and library example after `install`. Audit runtime and test dependency inventory against public vulnerability data, inspect licenses, scan git content for secrets. Publish only project files and synthetic fixtures. No claim of Maven Central publication (GitHub release and local Maven installation only), signed artifacts or notarization.

Always preserve existing workspace projects. Ask only for new costs, credentials, or account privilege expansion; the user authorized implementation, public GitHub upload and verification. Requirements and implementation choices are delegated for autonomous completion.
