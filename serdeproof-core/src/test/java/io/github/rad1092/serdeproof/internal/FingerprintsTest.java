package io.github.rad1092.serdeproof.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.*;

class FingerprintsTest {
    @TempDir Path temp;
    @Test void framedClasspathDistinguishesEntryBoundariesFromFilenames() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("dir"));
        Path empty = Files.createDirectory(temp.resolve("empty"));
        Path file = Files.writeString(temp.resolve("file.jar"), "same bytes");
        Files.writeString(directory.resolve("ENTRY"), "same bytes");
        assertNotEquals(Fingerprints.classpath(List.of(directory)), Fingerprints.classpath(List.of(empty, file)));
        assertNotEquals(Fingerprints.classpath(List.of(empty, file)), Fingerprints.classpath(List.of(file, empty)));
    }
    @Test void doesNotSilentlyIgnoreDeepClasspathContents() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path deep = root;
        for (int i = 0; i < 64; i++) deep = Files.createDirectory(deep.resolve("d"));
        Files.writeString(deep.resolve("deep.class"), "would otherwise be invisible");
        assertThrows(IOException.class, () -> Fingerprints.classpath(List.of(root)));
    }
    @Test void namesAndContentsMatterButAbsoluteLocationsDoNot() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Files.writeString(first.resolve("a.class"), "original");
        Files.writeString(second.resolve("a.class"), "original");
        assertEquals(Fingerprints.classpath(List.of(first)), Fingerprints.classpath(List.of(second)));
        Files.writeString(second.resolve("a.class"), "changed");
        assertNotEquals(Fingerprints.classpath(List.of(first)), Fingerprints.classpath(List.of(second)));
    }
    @Test void literalUnixBackslashIsNotAPathSeparator() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path literal = Files.createDirectory(temp.resolve("literal"));
        Path nested = Files.createDirectory(temp.resolve("nested"));
        Files.writeString(literal.resolve("a\\b"), "same bytes");
        Files.createDirectory(nested.resolve("a"));
        Files.writeString(nested.resolve("a/b"), "same bytes");
        assertNotEquals(Fingerprints.classpath(List.of(literal)), Fingerprints.classpath(List.of(nested)));
    }
    @Test void implicitManifestClasspathCannotBypassContentFingerprinting() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "untracked.jar");
        Path jar = temp.resolve("linked.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        assertThrows(IOException.class, () -> Fingerprints.classpath(List.of(jar)));
    }
}
