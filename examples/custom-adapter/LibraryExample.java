import io.github.rad1092.serdeproof.MigrationLab;
import io.github.rad1092.serdeproof.Report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Compile and run against the installed library, not the reactor's target/classes. */
public final class LibraryExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("manifest.json output-directory");
        Report report = new MigrationLab().run(Path.of(args[0]), LocalDate.of(2026, 10, 8), false);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.json"), report.json());
        Files.writeString(output.resolve("junit.xml"), report.junitXml());
        if (report.exitCode() != 0) throw new IllegalStateException("Unexpected migration findings: " + report.exitCode());
        System.out.println("Installed library and application adapter completed successfully.");
    }
}
