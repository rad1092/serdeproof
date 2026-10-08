package io.github.rad1092.serdeproof.api;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** One-request JVM bootstrap. Run through the SerdeProof supervisor, not directly. */
public final class AdapterMain {
    private AdapterMain() { }

    public static void main(String[] args) {
        int exitCode = execute(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    private static int execute(String[] args) {
        // Keep protocol stdout private before loading adapter classes (including static initializers).
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            if (args.length != 2) return WireProtocol.EXIT_FAILURE;
            int maxBytes = Integer.parseInt(args[1]);
            WireProtocol.checkLimit(maxBytes);
            // Construct before reading to ensure deadlines also cover adapter startup and blocked stdin.
            Class<?> adapterType = Class.forName(args[0]);
            SerializerAdapter adapter = (SerializerAdapter) adapterType.getConstructor().newInstance();
            WireProtocol.Request request = WireProtocol.readRequest(System.in, maxBytes);
            WireProtocol.Response response;
            try {
                AdapterResult result = adapter.evaluate(request.type(), request.input(), request.configuration());
                response = new WireProtocol.Response(true, result.output(), result.observation());
            } catch (RejectedInputException rejected) {
                response = new WireProtocol.Response(false, new byte[0], new byte[0]);
            }
            WireProtocol.writeResponse(protocolOut, response, maxBytes);
            return 0;
        } catch (WireProtocol.LimitException limit) {
            return WireProtocol.EXIT_OUTPUT_LIMIT;
        } catch (Throwable failure) {
            // Adapter messages, stack traces and paths may contain fixture secrets. Never forward them.
            return WireProtocol.EXIT_FAILURE;
        }
    }
}
