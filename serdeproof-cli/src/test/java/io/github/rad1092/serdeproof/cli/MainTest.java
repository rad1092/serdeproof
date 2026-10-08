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
