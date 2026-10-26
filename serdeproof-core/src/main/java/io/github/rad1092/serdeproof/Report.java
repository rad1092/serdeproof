package io.github.rad1092.serdeproof;

/**
 * A deterministic, value-free comparison report and its command-line exit status.
 * {@code markdown} is a short summary of the same report for pull requests and CI job summaries.
 */
public record Report(String json, String junitXml, String markdown, int exitCode) {
    /** The report without a Markdown summary, as built by 0.1.x. */
    public Report(String json, String junitXml, int exitCode) {
        this(json, junitXml, "", exitCode);
    }
}
