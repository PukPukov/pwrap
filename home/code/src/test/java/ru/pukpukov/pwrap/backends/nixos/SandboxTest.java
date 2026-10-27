package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** test_sandbox.py with real descriptors, posix_spawn and helper reaping. */
@EnabledInNativeImage
class SandboxTest {
    
    final Path root, arguments;
    final PlatformConfig config;
    
    SandboxTest(@TempDir Path root) throws IOException {
        this.root = root;
        arguments = root.resolve("arguments");
        Path bwrap = script(root.resolve("bwrap"), "printf '%s\\n' \"$@\" > " + quote(arguments) + "\n");
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("bwrap", bwrap.toString());
        raw.addProperty("filter", "/dev/null");
        config = new PlatformConfig(raw);
    }
    
    private Posix.Child helper(String body) throws Exception {
        return Posix.spawn(List.of(script(root.resolve("helper"), body).toString()), Map.of(), List.of(), 0, 1, 2);
    }
    
    @Test void test_payload_requires_pin_phase_and_empty_authority_needs_no_dac_child() throws Exception {
        Path temp;
        try (var payload = new Sandbox(config, new Lifetime(root))) {
            temp = payload.temp;
            assertThrows(IllegalArgumentException.class, () -> payload.start(List.of("/command"), List.of()));
            assertFalse(Files.exists(arguments));
            payload.pin(List.of(), "/home/test", List.of());
            try (var child = payload.start(List.of("/command", ""), List.of())) { assertEquals(0, child.waitFor()); }
            var argv = Files.readAllLines(arguments);
            assertEquals(List.of("/command", ""), argv.subList(argv.size() - 2, argv.size()));
        }
        assertFalse(Files.exists(temp));
    }
    
    @Test void test_transfer_pins_and_seccomp_then_close_parent_descriptors() throws Exception {
        try (var payload = new Sandbox(config, new Lifetime(root))) {
            payload.pin(List.of(), "/home/test", List.of());
            var pipe = payload.pipe();
            payload.fds.addAll(List.of(pipe));
            try (var child = payload.start(List.of("/command"), List.of())) { assertEquals(0, child.waitFor()); }
            assertTrue(payload.fds.isEmpty());
            assertTrue(Files.readAllLines(arguments).contains("--seccomp"));
        }
    }
    
    @Test void test_failed_spawn_closes_all_fds_and_terminates_prepared_helpers() throws Exception {
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("bwrap", root.resolve("nonexistent").toString());
        raw.addProperty("filter", "/dev/null");
        Posix.Child child = helper("exec " + tool("sleep") + " 100\n");
        Path temp;
        try (var payload = new Sandbox(new PlatformConfig(raw), new Lifetime(root))) {
            temp = payload.temp;
            payload.own(child);
            payload.pipe();
            payload.pin(List.of(), "/home/test", List.of());
            assertThrows(IOException.class, () -> payload.start(List.of("/command"), List.of()));
        }
        require(child.poll() != null, "Child was not reaped");
        assertFalse(Files.exists(temp));
    }
    
    @Test void test_setup_failure_before_spawn_still_owns_helper_lifetime() throws Exception {
        Posix.Child child = helper("exec " + tool("sleep") + " 100\n");
        assertThrows(IllegalArgumentException.class, () -> {
            try (var payload = new Sandbox(config, new Lifetime(root))) {
                payload.own(child);
                throw new IllegalArgumentException("setup");
            }
        });
        require(child.poll() != null, "Child was not reaped");
    }
    
    @Test void test_stuck_helper_is_killed_and_reaped() throws Exception {
        Path ready = root.resolve("ready");
        Posix.Child child = helper("trap '' TERM\nprintf ready > " + quote(ready) + "\nwhile :; do :; done\n");
        try (var payload = new Sandbox(config, new Lifetime(root))) {
            payload.own(child);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready));
        }
        assertEquals(-9, required(child.poll(), "Child was not reaped").intValue());
    }
    
}