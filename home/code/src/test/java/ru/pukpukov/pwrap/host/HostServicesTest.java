package ru.pukpukov.pwrap.host;

import module java.base;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure FastCGI framing checks; optional live service probes are not run by Maven. */
class HostServicesTest {
    
    @Test
    void record_header_preserves_id_kind_length_and_body() {
        byte[] body = "public fixture".getBytes(StandardCharsets.US_ASCII), message = HostServices.record(6, body);
        assertArrayEquals(new byte[]{1, 6, 0, 1, 0, 14, 0, 0}, Arrays.copyOf(message, 8));
        assertArrayEquals(body, Arrays.copyOfRange(message, 8, message.length));
        assertThrows(IllegalArgumentException.class, () -> HostServices.record(1, new byte[65536]));
    }
    
    @Test
    void length_encoding_switches_at_128_without_truncation() {
        var bytes = new ByteArrayOutputStream();
        HostServices.size(bytes, 127);
        HostServices.size(bytes, 128);
        HostServices.size(bytes, 65535);
        assertArrayEquals(new byte[]{127, (byte) 128, 0, 0, (byte) 128, (byte) 128, 0, (byte) 255, (byte) 255}, bytes.toByteArray());
        assertArrayEquals(new byte[]{1, 3, 'K', 'v', 'a', 'l'}, HostServices.params(Map.of("K", "val")));
    }
    
}