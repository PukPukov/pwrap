package ru.pukpukov.pwrap.core;

import module java.base;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import static org.junit.jupiter.api.Assertions.*;

@EnabledInNativeImage
class PipeDocumentTest {
    
    @Test void waits_for_eof_even_after_a_complete_json_value() throws Exception {
        var pipe = Posix.pipe();
        try (var input = pipe[0]; var output = pipe[1]; var executor = Executors.newSingleThreadExecutor()) {
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            var read = executor.submit(() -> input.readAll(64, 2000));
            assertThrows(TimeoutException.class, () -> read.get(50, TimeUnit.MILLISECONDS));
            output.write("\n".getBytes(StandardCharsets.UTF_8));
            output.close();
            assertArrayEquals("{}\n".getBytes(StandardCharsets.UTF_8), read.get(2, TimeUnit.SECONDS));
        }
    }
    
    @Test void empty_and_exact_limit_documents() throws Exception {
        for (String value : List.of("", "1234")) {
            var pipe = Posix.pipe();
            try (var input = pipe[0]; var output = pipe[1]) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.close();
                assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), input.readAll(4, 2000));
            }
        }
    }
    
    @Test void oversize_is_rejected_without_waiting_for_eof() throws Exception {
        var pipe = Posix.pipe();
        try (var input = pipe[0]; var output = pipe[1]) {
            output.write(new byte[5]);
            assertThrows(IllegalArgumentException.class, () -> input.readAll(4, 2000));
        }
    }
    
    @Test void partial_document_cannot_wait_forever() throws Exception {
        var pipe = Posix.pipe();
        try (var input = pipe[0]; var output = pipe[1]) {
            output.write(new byte[]{'{'});
            assertThrows(SocketTimeoutException.class, () -> input.readAll(64, 50));
        }
    }
    
}