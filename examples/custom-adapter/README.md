# Use the installed API with an existing DTO and mapper

This example is compiled by the repository's installation smoke test from installed Maven artifacts. `LibraryExample` invokes the installed core library, starts real adapter JVMs, and writes JSON and JUnit XML reports. `order.json` contains only synthetic data.

`src/example/Order.java` and `ServiceJson.java` stand in for code already present in an application. The integration is `OrderAdapter.java`: 46 lines including imports, validation and domain observations. It reuses the service mapper and DTO, distinguishes expected JSON input failures from broken definitions, and returns the production serialized output plus a deliberately selected observation. The total is normalized as an exact decimal string and the status uses enum identity.

For a real service, add this dependency to the adapter build after installing SerdeProof:

```xml
<dependency>
  <groupId>io.github.rad1092.serdeproof</groupId>
  <artifactId>serdeproof-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

For the calling Java application, also add `io.github.rad1092.serdeproof:serdeproof-core:0.1.0`. These coordinates are installed locally by `mvn install`; this project does not claim publication to Maven Central.

Keep the application's own Jackson version and module dependencies in the adapter build. Build baseline and candidate artifacts separately; do not put both serializer dependency trees in one adapter classpath. Use an ordinary thin adapter JAR plus explicit dependency paths, or an application-controlled fat JAR. Include the API JAR on each adapter's effective runtime classpath when it is not already bundled.

A manifest can point to the two builds:

```json
{
  "baseline": {
    "adapterClass": "example.OrderAdapter",
    "classpath": ["baseline/adapter.jar", "baseline/serdeproof-api-0.1.0.jar", "baseline/jackson-databind.jar", "baseline/jackson-core.jar", "baseline/jackson-annotations.jar"]
  },
  "candidate": {
    "adapterClass": "example.OrderAdapter",
    "classpath": ["candidate/adapter.jar", "candidate/serdeproof-api-0.1.0.jar", "candidate/jackson-databind.jar", "candidate/jackson-core.jar", "candidate/jackson-annotations.jar"]
  },
  "fixtures": [{"id":"order-1","type":"order","file":"order.json","hints":{"/status":"ENUM"}}]
}
```

The paths above describe your application's builds; they are not files bundled in this directory. Every dependency and any mapper modules must be listed explicitly. The installation smoke test creates a concrete equivalent manifest using installed artifacts, including a compiled adapter path with spaces and Unicode.

The core integration is:

```java
Report report = new MigrationLab().run(manifest, evaluationDate, false);
Files.writeString(output.resolve("report.json"), report.json());
Files.writeString(output.resolve("junit.xml"), report.junitXml());
// Fail your build for report.exitCode() != 0.
```

MigrationLab does not configure your mapper for you. The example has no external configuration and accepts absent configuration or an empty JSON object and rejects options; production adapters may instead read an application-specific configuration document. That file is fingerprinted by the engine. If the production factory reads environment variables, resources or system properties, make those inputs reproducible yourself as well.

A one-off JUnit or JsonUnit assertion is less setup when only two trees or snapshots need comparison. Existing ApprovalTests and JsonUnit integrations already serve that need. This harness adds separate dependency graphs, the four producer/consumer paths, explicit domain observations, bounded subprocesses, and expiring scoped review rules. Budget setup for two adapter builds and deciding useful observations; the 46-line adapter is not the whole migration effort.
