package io.github.rad1092.serdeproof.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MainTest {
    @TempDir Path temp;

    @Test void helpExplainsTrustAndScope() {
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.execute(new String[]{"--help"}, new PrintStream(bytes), System.err));
        assertTrue(bytes.toString().contains("not a sandbox"));
        assertTrue(bytes.toString().contains("only this corpus"));
    }

    @Test void failuresNameACodeAndAFixedHint() {
        var bytes = new ByteArrayOutputStream();
        assertEquals(2, Main.execute(new String[]{"run", "--manifest"}, System.out, new PrintStream(bytes)));
        assertTrue(bytes.toString().startsWith("Run failed (USAGE)."), bytes.toString());
        String pathCode = Main.failureCode(new java.nio.file.InvalidPathException("x", "y"));
        assertTrue(java.util.Set.of("PATH_INVALID", "PATH_ENCODING").contains(pathCode), pathCode);
    }

    @Test void invalidArgumentsNeverEchoUserData() {
        for (String[] args : new String[][]{
                {}, {"run", "--secret-value"}, {"run", "--manifest"},
                {"run", "--details", "--details"}, {"run", "--manifest", "private-customer-name"}}) {
            var bytes = new ByteArrayOutputStream();
            assertEquals(2, Main.execute(args, System.out, new PrintStream(bytes)));
            assertFalse(bytes.toString().contains("private-customer-name"));
            assertFalse(bytes.toString().contains("secret-value"));
        }
    }

    @Test void forceCannotOverwriteManifestThroughParentAlias() throws Exception {
        Path actual = Files.createDirectory(temp.resolve("actual"));
        Path alias = directoryAlias(actual);
        Path manifest = actual.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        var diagnostics = new ByteArrayOutputStream();
        int exit = Main.execute(new String[]{"run", "--manifest", manifest.toString(),
                "--json", alias.resolve("manifest.json").toString(), "--junit", temp.resolve("report.xml").toString(),
                "--force"}, new PrintStream(diagnostics), new PrintStream(diagnostics));
        assertEquals(2, exit);
        assertEquals("{}", Files.readString(manifest));
        assertFalse(Files.exists(temp.resolve("report.xml")));
    }

    @Test void forceCannotWriteBothReportsToOneExistingFileAlias() throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        Path actual = Files.createDirectory(temp.resolve("actual"));
        Path alias = directoryAlias(actual);
        Path report = actual.resolve("report.json");
        Files.writeString(report, "preserve-this-report");
        assertEquals(2, execute(manifest, report, alias.resolve("report.json"), "--force"));
        assertEquals("preserve-this-report", Files.readString(report));
    }

    @Test void rejectsFutureReportAliasWithoutCreatingDirectories() throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        Path actual = Files.createDirectory(temp.resolve("actual"));
        Path alias = directoryAlias(actual);
        Path report = actual.resolve("not-created/nested/report.json");
        assertEquals(2, execute(manifest, report, alias.resolve("not-created/nested/report.json"), "--force"));
        assertFalse(Files.exists(actual.resolve("not-created")));
    }

    @Test void forceCannotUseHardLinkToManifestAsReport() throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        Path alias = temp.resolve("manifest-alias.json");
        try { Files.createLink(alias, manifest); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Hard links unavailable in this test environment");
        }
        assertEquals(2, execute(manifest, alias, temp.resolve("report.xml"), "--force"));
        assertEquals("{}", Files.readString(manifest));
        assertEquals("{}", Files.readString(alias));
    }

    @Test void invalidEvaluationDateIsRedactedAndCreatesNoReports() throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        Path json = temp.resolve("report.json"), junit = temp.resolve("report.xml");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(2, Main.execute(new String[]{"run", "--manifest", manifest.toString(),
                "--json", json.toString(), "--junit", junit.toString(), "--as-of", "private-customer-date"},
                new PrintStream(diagnostics), new PrintStream(diagnostics)));
        assertFalse(diagnostics.toString().contains("private-customer-date"));
        assertFalse(diagnostics.toString().contains(temp.toString()));
        assertFalse(Files.exists(json));
        assertFalse(Files.exists(junit));
    }

    @Test void existingReportsRemainProtectedWithoutForce() throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, "{}");
        Path json = temp.resolve("report.json");
        Files.writeString(json, "original");
        assertEquals(2, execute(manifest, json, temp.resolve("report.xml")));
        assertEquals("original", Files.readString(json));
        assertFalse(Files.exists(temp.resolve("report.xml")));
    }

    @Test void forceCannotReplaceDeclaredFixtureConfigClasspathOrJava() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String role : java.util.List.of("fixture", "configuration", "classpath", "java")) {
            Path input = temp.resolve(role + ".bin");
            Files.writeString(input, "preserve-input-" + role);
            var adapter = new java.util.LinkedHashMap<String, Object>();
            adapter.put("adapterClass", "missing.Adapter");
            Path fixture = role.equals("fixture") ? input : temp.resolve(role + "-fixture.json");
            if (!fixture.equals(input)) Files.writeString(fixture, "{}");
            Path classpath = role.equals("classpath") ? input : temp.resolve(role + "-adapter.jar");
            if (!classpath.equals(input)) Files.writeString(classpath, "synthetic-jar-not-executed");
            adapter.put("classpath", java.util.List.of(classpath.toString()));
            if (role.equals("configuration") || role.equals("java")) adapter.put(role, input.toString());
            Path manifest = temp.resolve(role + "-manifest.json");
            mapper.writeValue(manifest.toFile(), java.util.Map.of("baseline", adapter, "candidate", adapter,
                    "fixtures", java.util.List.of(java.util.Map.of("id", "sample", "type", "test", "file", fixture.toString()))));
            Path other = temp.resolve(role + "-report.xml");
            assertEquals(2, execute(manifest, input, other, "--force"));
            assertEquals("preserve-input-" + role, Files.readString(input));
            assertFalse(Files.exists(other));
        }
    }

    @Test void forceCannotReplaceFixtureThroughHardLinkOrParentAlias() throws Exception {
        Path inputDir = Files.createDirectory(temp.resolve("inputs"));
        Path alias = directoryAlias(inputDir);
        Path fixture = inputDir.resolve("fixture.json");
        Files.writeString(fixture, "{}");
        Path manifest = simpleManifest(fixture);
        assertEquals(2, execute(manifest, alias.resolve("fixture.json"), temp.resolve("alias.xml"), "--force"));
        assertEquals("{}", Files.readString(fixture));
        assertFalse(Files.exists(temp.resolve("alias.xml")));
        Path hardlink = temp.resolve("fixture-link.json");
        try { Files.createLink(hardlink, fixture); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Hard links unavailable in this test environment");
        }
        assertEquals(2, execute(manifest, hardlink, temp.resolve("hardlink.xml"), "--force"));
        assertEquals("{}", Files.readString(fixture));
        assertEquals("{}", Files.readString(hardlink));
    }

    @Test void refusesNewOutputWithinDeclaredClasspathDirectory() throws Exception {
        Path fixture = temp.resolve("fixture.json"); Files.writeString(fixture, "{}");
        Path classes = Files.createDirectory(temp.resolve("classes"));
        Path alias = directoryAlias(classes);
        var adapter = java.util.Map.of("adapterClass", "missing.Adapter", "classpath", java.util.List.of(classes.toString()));
        Path manifest = temp.resolve("manifest.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(manifest.toFile(), java.util.Map.of(
                "baseline", adapter, "candidate", adapter,
                "fixtures", java.util.List.of(java.util.Map.of("id", "sample", "type", "test", "file", fixture.toString()))));
        Path destination = alias.resolve("nested/new.json");
        assertEquals(2, execute(manifest, destination, temp.resolve("report.xml"), "--force"));
        assertFalse(Files.exists(classes.resolve("nested")));
    }

    @Test void strictCoverageFlagWritesConsistentReportsAndForceStillReplacesOnlyReports() throws Exception {
        Path fixture = temp.resolve("fixture.json"); Files.writeString(fixture, "{}");
        Path manifest = simpleManifest(fixture);
        Path json = temp.resolve("report.json"), junit = temp.resolve("report.xml");
        assertEquals(0, execute(manifest, json, junit));
        assertTrue(Files.readString(json).contains("UNTESTED_BOTH_REJECTED"));
        assertEquals(2, execute(manifest, json, junit, "--force", "--fail-on-untested"));
        assertTrue(Files.readString(json).contains("UNTESTED_FIXTURES"));
        assertTrue(Files.readString(junit).contains("UNTESTED_FIXTURES"));
        assertEquals("{}", Files.readString(fixture));
    }

    private Path simpleManifest(Path fixture) throws Exception {
        var cp = java.util.List.of(
                Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(io.github.rad1092.serdeproof.api.SerializerAdapter.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toString());
        var adapter = java.util.Map.of("adapterClass", Reject.class.getName(), "classpath", cp);
        Path manifest = temp.resolve("manifest.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(manifest.toFile(), java.util.Map.of(
                "baseline", adapter, "candidate", adapter,
                "fixtures", java.util.List.of(java.util.Map.of("id", "sample", "type", "test", "file", fixture.toString()))));
        return manifest;
    }

    public static final class Reject implements io.github.rad1092.serdeproof.api.SerializerAdapter {
        public io.github.rad1092.serdeproof.api.AdapterResult evaluate(String type, byte[] input, byte[] config)
                throws io.github.rad1092.serdeproof.api.RejectedInputException {
            throw new io.github.rad1092.serdeproof.api.RejectedInputException();
        }
    }

    private Path directoryAlias(Path actual) throws IOException {
        Path alias = temp.resolve("alias");
        try { Files.createSymbolicLink(alias, actual); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Directory symlinks unavailable in this test environment");
        }
        return alias;
    }

    private int execute(Path manifest, Path json, Path junit, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("run", "--manifest", manifest.toString(),
                "--json", json.toString(), "--junit", junit.toString()));
        args.addAll(java.util.List.of(extra));
        var diagnostics = new ByteArrayOutputStream();
        return Main.execute(args.toArray(String[]::new), new PrintStream(diagnostics), new PrintStream(diagnostics));
    }
}
