package io.github.rad1092.serdeproof.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WireProtocolTest {
    @Test void requestRoundTripsUtf8TypeAndArbitraryConfiguration() throws Exception {
        byte[] input = "{\"name\":\"한글 🌍\"}".getBytes(StandardCharsets.UTF_8);
        byte[] config = {0, 1, 2, -1};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WireProtocol.writeRequest(out, "유형 🌍", input, config, 1024);
        WireProtocol.Request decoded = WireProtocol.readRequest(new ByteArrayInputStream(out.toByteArray()), 1024);
        assertEquals("유형 🌍", decoded.type());
        assertArrayEquals(input, decoded.input());
        assertArrayEquals(config, decoded.configuration());
    }

    @Test void responseRequiresKnownStatusAndExactFraming() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WireProtocol.writeResponse(out, new WireProtocol.Response(true, new byte[]{1}, new byte[]{2}), 8);
        byte[] frame = out.toByteArray();
        assertTrue(WireProtocol.readResponse(new ByteArrayInputStream(frame), 8).accepted());
        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertThrows(IOException.class, () -> WireProtocol.readResponse(new ByteArrayInputStream(trailing), 8));
        assertThrows(IOException.class, () -> WireProtocol.readResponse(new ByteArrayInputStream(Arrays.copyOf(frame, 9)), 8));
        frame[4] = 9;
        assertThrows(IOException.class, () -> WireProtocol.readResponse(new ByteArrayInputStream(frame), 8));
    }

    @Test void hostileLengthIsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(0x53505231);
        data.writeByte(1);
        data.writeInt(Integer.MAX_VALUE);
        assertThrows(WireProtocol.LimitException.class,
                () -> WireProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), 16));
        out.reset();
        data.writeInt(0x53505231);
        data.writeByte(1);
        data.writeInt(-1);
        assertThrows(WireProtocol.LimitException.class,
                () -> WireProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), 16));
    }

    @Test void rejectedResponseCannotCarrySensitiveBytes() {
        assertThrows(IOException.class, () -> WireProtocol.writeResponse(new ByteArrayOutputStream(),
                new WireProtocol.Response(false, new byte[]{1}, new byte[0]), 16));
    }

    @Test void malformedUtf8TypeIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(0x53505131);
        data.writeInt(1);
        data.writeByte(0xFF);
        data.writeInt(0);
        data.writeInt(0);
        assertThrows(IOException.class, () -> WireProtocol.readRequest(new ByteArrayInputStream(out.toByteArray()), 16));
    }

    @Test void resultOwnsItsArraysAndHardCeilingIsEnforced() {
        byte[] bytes = {1};
        AdapterResult result = new AdapterResult(bytes, bytes);
        bytes[0] = 2;
        result.output()[0] = 3;
        assertArrayEquals(new byte[]{1}, result.output());
        assertArrayEquals(new byte[]{1}, result.observation());
        assertThrows(IllegalArgumentException.class, () -> WireProtocol.checkLimit(0));
        assertThrows(IllegalArgumentException.class, () -> WireProtocol.checkLimit(WireProtocol.HARD_MAX_BYTES + 1));
    }
}
