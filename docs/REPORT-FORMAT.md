# Report and manifest reference

The public Java entry point is `new MigrationLab().run(manifestPath, asOfDate, details)`.
`Report` is a top-level record in `io.github.rad1092.serdeproof` exposing `json()`,
`junitXml()` and `exitCode()`. A run uses fresh JVMs for each adapter evaluation.
The caller supplies the evaluation date explicitly so rule expiry is reproducible.

## Manifest

The root contains `baseline`, `candidate`, `fixtures`, optional `rules` and optional
`limits`. Unknown fields, duplicate JSON members and trailing documents are errors.
Paths resolve relative to the manifest file, including paths with spaces and Unicode.
Classpath entries are literal files or directories; no wildcard or shell expansion is
performed. Include the API JAR and the adapter's actual transitive runtime dependencies.

Each adapter contains `adapterClass` and a `classpath` array, with optional `java`,
`jvmArgs` array and `configuration` file. The default executable is the running Java
installation; the default configuration bytes are `{}`. Configuration is opaque to the
engine, so an adapter may define its own format. It is still size-bounded and hashed.

Each fixture contains an explicit `id`, `type`, `file` and optional `hints` object.
IDs use 1–128 ASCII letters, digits, dots, underscores or hyphens, starting with a letter
or digit. Types are nonblank strings up to 512 characters. The fixture bytes are passed
unchanged to each adapter: intentionally malformed/trailing payloads can test acceptance.
Hints map exact RFC 6901 observation pointers to `DATE` or `ENUM`. They label changed
scalar observations; they do not normalize dates, infer enums or alter output comparison.

| Limit | Default | Accepted maximum |
| --- | ---: | ---: |
| `maxBytes` per input/configuration/adapter field | 1,048,576 | 16,777,216 |
| `timeoutMillis` per JVM evaluation | 10,000 | 300,000 |
| `maxFixtures` | 1,000 | 10,000 |
| `maxDifferences` across the run | 10,000 | 100,000 |
| `maxCorpusBytes` | 268,435,456 | 1,073,741,824 |

The manifest itself is capped at 1 MiB. Fixture sizes are checked before execution and
again when read; only the current fixture and its adapter results are retained. The
engine has a separate conservative 16 MiB difference-metadata budget, allowing for
JSON escaping. Each rendered report is also capped at 16 MiB; if exceeded, it is replaced
by a compact `REPORT_SIZE_LIMIT` failure report. Each individual comparison
also limits pointer metadata to 4 MiB and pointers to 8,192 characters. Budget exhaustion
sets `truncated: true` and exits 2; a partial report is never a successful result.
The strict JSON reader accepts UTF-8 without a leading byte-order mark; malformed UTF-8,
UTF-16, and UTF-32 documents are rejected. It caps container depth at 128, number tokens at 1,000 characters,
string tokens at 1 MiB, member names at 64 KiB, and total nodes (containers plus scalars)
at 100,000 per document. Documents exceeding these limits
are infrastructure failures even when an adapter can otherwise produce them.

## Findings and matrix

`fixtures[].baseline` and `.candidate` record acceptance or infrastructure status.
Each accepted producer's JSON output is read by both adapters. `matrix` has four cells:
`baseline-to-baseline`, `baseline-to-candidate`, `candidate-to-baseline` and
`candidate-to-candidate`. A rejected or failed producer yields a `NOT_RUN` cell with
explicit untested coverage. The engine compares a producer's observation to its consumer's
observation, revealing same-version loss as well as upgrade/downgrade incompatibility.

Initial comparisons use the `baseline-to-candidate` direction:

| Kind | Evidence |
| --- | --- |
| `ACCEPTANCE` | One adapter accepts the original fixture and the other rejects it. |
| `OUTPUT_*` | The two serialized JSON outputs differ. |
| `OBSERVATION_*` | The two user-defined domain observations differ. |
| `ROUNDTRIP_ACCEPTANCE` | A consumer rejects an accepted producer's output. |
| `ROUNDTRIP_*` | Producer and consumer domain observations differ. |

Suffixes distinguish `MISSING`, `NULL`, `TYPE`, `VALUE`, `NUMERIC_VALUE` and
`NUMERIC_LEXICAL`. Missing takes precedence over null; null takes precedence over type.
Exact number tokens are retained: `1` versus `1.0` is lexical, while integers beyond
binary64 precision remain distinguishable by value. Mathematical value comparison
handles large decimal exponents without creating exponent-sized integers.
`DATE` and `ENUM` replace scalar-value suffixes at hinted observation pointers.
Lexical numeric changes retain `NUMERIC_LEXICAL` even at hinted pointers.

Arrays compare by index, not identity. Object member order and insignificant JSON
whitespace are ignored. Pointers use RFC 6901 escapes (`~0`, `~1`); the root is `""`.
Both adapters rejecting an original fixture records `UNTESTED_BOTH_REJECTED` and a
skipped JUnit test. Exit 0 does not imply those payload semantics were checked.

## Expected changes

A rule requires `id`, `fixture`, `kind`, `direction`, `pointer`, `reason` and an ISO
`expires` date. Scope is exact across all four dimensions; wildcards and duplicate
scopes are rejected. A rule is valid through its expiry date, inclusive. Matching active
rules mark findings expected. Expired and unused rules fail with exit 2, so obsolete
exceptions do not silently remain in a migration gate. Infrastructure errors cannot be
suppressed. Unmatched rules whose fixture could not finish because of an infrastructure
error or truncation are `NOT_EVALUATED`, also exit 2, rather than being labeled stale.
Reasons are required and exposed only in detailed reports.

## Privacy, determinism and reproduction

Default reports hash fixture IDs, types, JSON pointers, rule IDs and the fixture/pointer
fields in rule scopes. Difference kinds and matrix directions remain readable. Reports
contain no fixture values, serialized output values, raw adapter errors or absolute
paths. `details=true` reveals IDs, types, pointers and rule reasons; it still does not
include payload values or adapter exception messages. A reason written by a user can
itself contain sensitive information. Hashes leak equality and permit guessing of
low-entropy inputs. Review reports before publishing.

Reports have sorted object keys and deterministic fixture/rule/finding order. JUnit
XML is escaped, contains no captured process output and has no wall-clock durations.
The same corpus, adapters, environment and evaluation date produce the same report,
provided the adapters themselves are deterministic.

The report records manifest bytes, fixture bytes, configuration bytes, ordered
classpath contents, executable bytes and JVM-argument SHA-256 fingerprints. Directory
classpath identities incorporate sorted relative filenames and file bytes; reports do
not reveal these filenames. A classpath fingerprint is capped at 100,000 files and
1 GiB per adapter. Directory depth of 64 or more is rejected rather than silently omitted.
JARs with a nonempty manifest `Class-Path` are rejected: list each dependency explicitly
to avoid loading an untracked transitive JAR. Symbolic links inside a classpath directory are rejected because
untracked linked contents would weaken reproduction. Top-level classpath file paths
may refer to regular files through a symlink. Classpath and executable fingerprints
are rechecked after evaluation to detect ordinary concurrent edits. These checks do
not provide an atomic filesystem snapshot or detect a file changed and restored during
a run. OS name/version/architecture, host Java vendor/version and the host's default
time zone and locale language tag are included; a configured
adapter Java executable is identified by its byte fingerprint. The complete JDK installation,
files referenced by JVM arguments (for example agents or module paths), environment variables,
network data and arbitrary files opened by adapter code are not fingerprinted. Pin and record
those inputs separately when adapters depend on them.
Child JVM `-D` overrides remain represented by the JVM-argument fingerprint; host locale
and time zone metadata do not claim to report overridden child settings.

Exit codes: 0 means no unexpected findings or errors, 1 means unexpected compatibility
findings, and 2 means configuration, infrastructure, limit or rule-validation errors.
Interruption propagates as `InterruptedException` after process cleanup; CLI callers
map it to 130. Adapter code has normal local user privileges. This is dependency
isolation and bounded process supervision, not a sandbox or a proof of semantic equality.
