package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

@EnabledInNativeImage
class InstanceBoundaryTest {
    
    @Test void unavailable_execution_authority_does_not_discard_live_state(@TempDir Path root) throws Exception {
        boolean[] unavailable = {false};
        Instances instances = new Instances(root, new Instances.Executions() {
            public void validate(String identity) { assertEquals("worker-one", identity); }
            
            public boolean active(String identity) throws IOException {
                if (unavailable[0]) throw new IOException("Execution authority unavailable");
                return true;
            }
            
            public boolean contains(String identity, long pid) { return false; }
        });
        var codec = new Instances.Codec<>("test-v1", JsonObject.class, JsonObject.class);
        Path scratch;
        try (var first = instances.lease("app", codec, obj(), () -> obj(), true)) {
            scratch = Path.of(Json.required(first.snapshot.scratch(), "Missing scratch"));
            instances.register("app", "worker-one", "authority", Map.of(), Map.of(codec.key(), Json.encode(first.snapshot).getAsJsonObject()), true);
        }
        Path record = root.resolve("app/worker-one.json");
        String original = Files.readString(record);
        unavailable[0] = true;
        assertThrows(IOException.class, () -> instances.lease(
            "app", codec, obj(), () -> { throw new AssertionError("Unavailable authority recaptured"); }, true
        ));
        assertEquals(original, Files.readString(record));
        assertTrue(Files.isDirectory(scratch));
        unavailable[0] = false;
        instances.release("app", "worker-one");
        assertFalse(Files.exists(scratch));
    }
    
    @Test void live_non_systemd_execution_keeps_authority_and_scratch_until_both_owners_release(@TempDir Path root) throws Exception {
        Set<String> active = new HashSet<>();
        Instances.Executions executions = new Instances.Executions() {
            public void validate(String identity) { assertEquals("worker-one", identity); }
            
            public boolean active(String identity) {
                validate(identity);
                return active.contains(identity);
            }
            
            public boolean contains(String identity, long pid) {
                validate(identity);
                return active(identity) && pid == 42;
            }
        };
        Instances instances = new Instances(root, executions);
        var codec = new Instances.Codec<>("test-v1", JsonObject.class, JsonObject.class);
        var request = obj("intent", 1);
        int[] captures = {0};
        Path scratch;
        try (var first = instances.lease("app", codec, request, () -> obj("capture", ++captures[0]), true)) {
            scratch = Path.of(Json.required(first.snapshot.scratch(), "Missing scratch"));
            active.add("worker-one");
            instances.register("app", "worker-one", "authority", Map.of(), Map.of(codec.key(), Json.encode(first.snapshot).getAsJsonObject()), true);
        }
        try (var second = instances.lease("app", codec, request, () -> { throw new AssertionError("Live execution was recaptured"); }, true)) {
            assertEquals(1, captures[0]);
            assertTrue(instances.processInProfile("app", 42));
            assertFalse(instances.processInProfile("app", 43));
            assertThrows(IllegalArgumentException.class, () -> instances.register("app", null, "different", Map.of(), Map.of(), false));
            assertThrows(IllegalArgumentException.class, () -> instances.lease("app", codec, obj("intent", 2), () -> obj(), true));
            active.clear();
            instances.release("app", "worker-one");
            assertTrue(Files.exists(scratch));
        }
        assertFalse(Files.exists(scratch));
        assertFalse(instances.processInProfile("app", 42));
        try (var fresh = instances.lease("app", codec, request, () -> obj("capture", ++captures[0]), true)) { assertEquals(2, captures[0]); }
    }
    
}