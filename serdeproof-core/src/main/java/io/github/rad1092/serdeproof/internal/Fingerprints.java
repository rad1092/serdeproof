package io.github.rad1092.serdeproof.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

/** Content identities. Names affect directory identities but are never published. */
public final class Fingerprints {
    private Fingerprints() {}
    public static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    public static String text(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String file(Path path) throws IOException {
        if (Files.size(path) > 1024L * 1024 * 1024) throw new IOException("FINGERPRINT_SIZE_LIMIT");
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkInterrupted();
                total += n;
                if (total > 1024L * 1024 * 1024) throw new IOException("FINGERPRINT_SIZE_LIMIT");
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
    public static String classpath(List<Path> paths) throws IOException {
        MessageDigest md = digest();
        add(md, "serdeproof-classpath-v1");
        long totalBytes = 0;
        int totalFiles = 0;
        for (Path path : paths) {
            checkInterrupted();
            add(md, "ENTRY");
            if (Files.isDirectory(path)) {
                add(md, "DIRECTORY");
                List<Path> entries = new ArrayList<>();
                try (var stream = Files.walk(path, 64)) {
                    var it = stream.iterator();
                    while (it.hasNext()) {
                        checkInterrupted();
                        Path entry = it.next();
                        if (Files.isSymbolicLink(entry)) throw new IOException("CLASSPATH_SYMLINK_UNSUPPORTED");
                        if (Files.isDirectory(entry) && path.relativize(entry).getNameCount() >= 64)
                            throw new IOException("CLASSPATH_DEPTH_LIMIT");
                        if (Files.isRegularFile(entry)) {
                            if (++totalFiles > 100000) throw new IOException("CLASSPATH_FILE_LIMIT");
                            entries.add(entry);
                        }
                    }
                }
                entries.sort(Comparator.comparing(p -> relativeName(path, p)));
                add(md, Integer.toString(entries.size()));
                for (Path entry : entries) {
                    totalBytes += Files.size(entry);
                    if (totalBytes > 1024L * 1024 * 1024) throw new IOException("CLASSPATH_SIZE_LIMIT");
                    add(md, relativeName(path, entry));
                    add(md, file(entry));
                }
            } else if (Files.isRegularFile(path)) {
                add(md, "FILE");
                if (++totalFiles > 100000) throw new IOException("CLASSPATH_FILE_LIMIT");
                totalBytes += Files.size(path);
                if (totalBytes > 1024L * 1024 * 1024) throw new IOException("CLASSPATH_SIZE_LIMIT");
                rejectImplicitClasspath(path);
                add(md, file(path));
            } else throw new IOException("CLASSPATH_NOT_FOUND");
            add(md, "END_ENTRY");
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static void add(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        md.update(bytes);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("FINGERPRINT_INTERRUPTED");
    }
    private static String relativeName(Path root, Path entry) {
        List<String> components = new ArrayList<>();
        root.relativize(entry).forEach(p -> components.add(p.toString()));
        return String.join("/", components);
    }
    private static void rejectImplicitClasspath(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            var manifest = jar.getManifest();
            String linked = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (linked != null && !linked.isBlank()) throw new IOException("CLASSPATH_MANIFEST_LINK_UNSUPPORTED");
        } catch (ZipException nonArchive) {
            // An ordinary non-archive file cannot contribute a manifest Class-Path.
        }
    }
}
