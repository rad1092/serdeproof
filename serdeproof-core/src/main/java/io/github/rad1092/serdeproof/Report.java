package io.github.rad1092.serdeproof;

/** A deterministic, value-free comparison report and its command-line exit status. */
public record Report(String json, String junitXml, int exitCode) {}
