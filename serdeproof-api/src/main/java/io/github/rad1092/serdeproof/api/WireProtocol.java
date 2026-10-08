package io.github.rad1092.serdeproof.api;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Internal versioned transport shared by the dependency-free adapter bootstrap and supervisor.
 * Integers are big endian. Each request/response must occupy the entire stream, with no trailing bytes.
 * This class is public for transport reuse; applications normally implement {@link SerializerAdapter}.
 */
public final class WireProtocol {
    public static final int HARD_MAX_BYTES = 16 * 1024 * 1024;
    public static final int EXIT_FAILURE = 70;
    public static final int EXIT_OUTPUT_LIMIT = 75;
    private static final int REQUEST_MAGIC = 0x53505131; // SPQ1
    private static final int RESPONSE_MAGIC = 0x53505231; // SPR1

    private WireProtocol() { }

    public record Request(String type, byte[] input, byte[] configuration) { }
    public record Response(boolean accepted, byte[] output, byte[] observation) { }

    public static void checkLimit(int maxBytes) {
        if (maxBytes < 1 || maxBytes > HARD_MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 and 16777216");
        }
    }

    public static int maxResponseBytes(int maxBytes) {
        checkLimit(maxBytes);
        return 13 + 2 * maxBytes;
    }

    public static void writeRequest(OutputStream stream, String type, byte[] input,
                                    byte[] configuration, int maxBytes) throws IOException {
        checkLimit(maxBytes);
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        checkLength(typeBytes.length, maxBytes);
        checkLength(input.length, maxBytes);
        checkLength(configuration.length, maxBytes);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(REQUEST_MAGIC);
        writeBytes(out, typeBytes);
        writeBytes(out, input);
        writeBytes(out, configuration);
        out.flush();
    }

    public static Request readRequest(InputStream stream, int maxBytes) throws IOException {
        checkLimit(maxBytes);
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != REQUEST_MAGIC) throw new IOException("Invalid request header");
        byte[] typeBytes = readBytes(in, maxBytes);
        String type;
        try {
            type = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(typeBytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("Invalid request type encoding");
        }
        byte[] input = readBytes(in, maxBytes);
        byte[] configuration = readBytes(in, maxBytes);
        requireEnd(in);
        return new Request(type, input, configuration);
    }

    public static void writeResponse(OutputStream stream, Response response, int maxBytes)
            throws IOException {
        checkLimit(maxBytes);
        checkLength(response.output().length, maxBytes);
        checkLength(response.observation().length, maxBytes);
        if (!response.accepted() && (response.output().length != 0 || response.observation().length != 0)) {
            throw new IOException("Rejected response must have empty fields");
        }
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(RESPONSE_MAGIC);
        out.writeByte(response.accepted() ? 1 : 2);
        writeBytes(out, response.output());
        writeBytes(out, response.observation());
        out.flush();
    }

    public static Response readResponse(InputStream stream, int maxBytes) throws IOException {
        checkLimit(maxBytes);
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != RESPONSE_MAGIC) throw new IOException("Invalid response header");
        int status = in.readUnsignedByte();
        if (status != 1 && status != 2) throw new IOException("Invalid response status");
        byte[] output = readBytes(in, maxBytes);
        byte[] observation = readBytes(in, maxBytes);
        if (status == 2 && (output.length != 0 || observation.length != 0)) {
            throw new IOException("Rejected response must have empty fields");
        }
        requireEnd(in);
        return new Response(status == 1, output, observation);
    }

    private static void checkLength(int length, int maxBytes) throws LimitException {
        if (length < 0 || length > maxBytes) throw new LimitException();
    }

    private static byte[] readBytes(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readInt();
        checkLength(length, maxBytes);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated field");
        return bytes;
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("Trailing protocol bytes");
    }

    public static final class LimitException extends IOException {
        public LimitException() { super("Protocol field exceeds limit"); }
    }
}
