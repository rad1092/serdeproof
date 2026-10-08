package io.github.rad1092.serdeproof.api;

import java.util.Objects;

/** JSON UTF-8 bytes for serialized output and application-defined domain observation. */
public record AdapterResult(byte[] output, byte[] observation) {
    public AdapterResult {
        output = Objects.requireNonNull(output, "output").clone();
        observation = Objects.requireNonNull(observation, "observation").clone();
    }

    @Override public byte[] output() { return output.clone(); }
    @Override public byte[] observation() { return observation.clone(); }
}
