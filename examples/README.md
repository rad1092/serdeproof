# Executable examples

The corpus is synthetic and intentionally small. It contains no customer data. From the repository root, build with `./mvnw -B -ntp clean install` (`.\mvnw.cmd` in Windows PowerShell) before running these manifests; adapter classpaths are relative to each manifest. The demo JARs each carry exactly one Jackson version, so the application DTO classes and serializer never share a JVM across baseline and candidate.

```sh
java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar run \
  --manifest examples/demo.json --json report.json --junit junit.xml \
  --as-of 2026-10-08 --details
```

Exit 1 is expected for this command because the demo intentionally contains migration findings. For an extracted release archive, use `lib/serdeproof-cli-0.1.0.jar` and `examples/release-demo.json` instead. Choose fresh report filenames or explicitly use `--force` to replace previous reports.

| Manifest | Purpose | Intended gate result |
| --- | --- | --- |
| `demo.json` | Jackson 2.22.3 → 3.2.3 defaults against 17 fixtures | Differences, exit 1 |
| `compatible.json` | A small corpus of compatible application behavior | No differences, exit 0 |
| `jackson-config-change.json` | Candidate mapper explicitly enables decimal numbers and omits null properties | Differences and roundtrip loss, exit 1 |
| `same-version-loss.json` | Both sides use Jackson 2 with a deliberately lossy money module setting | Same-version roundtrip loss, exit 1 |
| `jackson-reviewed.json` | Same defaults corpus, acknowledging only additive unknown fields on one DTO | Other differences remain, exit 1 |

## What the adapters measure

`demo.Jackson2Adapter` and `demo.Jackson3Adapter` deserialize into real Java DTOs and serialize the resulting DTO through their respective application mapper. Their observation is separately constructed from the DTO: enum identity uses `name()`, dates use an ISO `Instant`, integers remain `BigInteger`, and decimal values avoid conversion through `double`. This lets the compatibility matrix distinguish a changed wire representation from changed observed domain state.

The `Money` type has a custom Jackson module on both sides. Its normal serializer preserves the decimal string. Only `same-version-loss.json` enables the deliberately lossy two-decimal rounding option. Input `12.345` is observed before serialization; the emitted payload contains `12.34`; reading that payload back reveals an observed loss even though old/new output JSON is identical. Comparing only the two serialized output snapshots would miss this loss.

The `NullableValue` DTO initializes its property to `fallback`. A missing property leaves that default, while explicit null replaces it. Enabling null omission in the candidate makes an explicit null disappear on the wire; a consumer then reconstructs `fallback`. The typed numeric case preserves `9007199254740993` and `1.0000000000000001`; the untyped case exposes the deliberate `Double` → `BigDecimal` configuration change, including the Java representation in its observation.

Jackson 3 changes the default treatment of unknown properties, trailing tokens, primitive nulls, enum `toString()`, and dates. The corresponding fixtures isolate those behaviors. See the [official 3.0 configuration changes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0). An acceptance mismatch is reported without inventing an observation for the rejected side. Both rejected is untested semantic coverage.

The `한글 공백.json` fixture deliberately exercises a Unicode filename containing a space. Its explicit report ID is the portable `unicode-path`.

## Historical regression guards

These are regression guards for fixed historical bugs, not a claim that the pinned versions are broken:

* [#3906](https://github.com/FasterXML/jackson-databind/issues/3906): hidden record visibility. The demo disables accessor detection and explicitly enables field access. Primitive-null strictness is disabled for this one type in both adapters so this test isolates record visibility rather than a separate 3.0 default change. The empty object yields a record containing null and zero and is readable after serialization.
* [#5729](https://github.com/FasterXML/jackson-databind/issues/5729): a negative millisecond timestamp supplied as a string. The upstream issue is closed and assigned to 2.21.2. Both pinned adapters must observe `1926-03-05T13:12:10.065Z` for the synthetic historical payload. The different numeric/string date output defaults are still visible.

## Review rules are decisions

`jackson-reviewed.json` acknowledges exactly fixture `unknown-field`, kind `ACCEPTANCE`, direction `baseline-to-candidate`, root pointer `""`. Its reason says additive fields are accepted for this one example DTO, and its expiry is 2027-03-31. It does not approve enum changes, date representation, roundtrip failures or later fixtures. The run date controls expiry; use an explicit evaluation date for reproducible historical runs.

Do not copy the approval merely to make a migration green. Record your own service owner decision, scope and review deadline. In ordinary public reports IDs and pointers are hashed, and reasons are omitted. Detail mode intentionally reveals these metadata; neither report mode prints fixture values or adapter exception text.

## Bring an existing service

Start with [`custom-adapter`](custom-adapter/README.md). Compile a small adapter separately against each service revision's actual DTOs, mapper factory, modules and dependencies. Add representative local production-shaped fixtures after removing secrets. Supply observations for the business properties whose meaning matters to your migration.

Adapter code has normal local user privileges. Separate JVMs provide dependency separation and failure supervision, not a security sandbox. Passing this corpus means only that these payloads and these observations met these rules; it is never a proof of full semantic equivalence.
