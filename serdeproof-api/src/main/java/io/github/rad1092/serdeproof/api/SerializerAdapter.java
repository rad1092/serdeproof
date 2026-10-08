package io.github.rad1092.serdeproof.api;

/**
 * Application-owned serializer integration. Implementations need a public no-argument constructor.
 * Each evaluation runs in a fresh JVM; retain the application's real DTOs, modules and mapper settings.
 * Adapter code is trusted local code, not sandboxed code.
 */
@FunctionalInterface
public interface SerializerAdapter {
    /**
     * Reads a payload and returns its serialized form and application-defined observation as JSON.
     * Throw {@link RejectedInputException} for an expected payload rejection. Other exceptions indicate
     * infrastructure failure. The configuration bytes are opaque to SerdeProof and may be empty.
     */
    AdapterResult evaluate(String type, byte[] input, byte[] configuration) throws Exception;
}
