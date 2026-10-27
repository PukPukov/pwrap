package ru.pukpukov.pwrap.host;

import module java.base;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixtureBusTest {
    
    @Test void uint_after_string_is_aligned_for_every_string_length() {
        for (int length = 0; length < 16; length++) {
            String text = "a".repeat(length);
            var reader = new FixtureBus.Reader(
                new FixtureBus.Writer().string(text).uint(42).toByteArray(), ByteOrder.LITTLE_ENDIAN
            );
            assertEquals(text, reader.string());
            reader.align(4);
            assertEquals(42, reader.data.getInt());
            assertFalse(reader.data.hasRemaining());
        }
    }
    
    @Test void header_variants_preserve_alignment_and_terminators() {
        var reader = new FixtureBus.Reader(
            new FixtureBus.Writer()
                .field(1, 'o', "/fixture")
                .integerField(5, 123)
                .field(8, 'g', "as")
                .toByteArray(),
            ByteOrder.LITTLE_ENDIAN
        );
        assertEquals(1, reader.data.get());
        assertEquals("o", reader.signature());
        assertEquals("/fixture", reader.string());
        reader.align(8);
        assertEquals(5, reader.data.get());
        assertEquals("u", reader.signature());
        reader.align(4);
        assertEquals(123, reader.data.getInt());
        reader.align(8);
        assertEquals(8, reader.data.get());
        assertEquals("g", reader.signature());
        assertEquals("as", reader.signature());
        assertFalse(reader.data.hasRemaining());
    }
    
}