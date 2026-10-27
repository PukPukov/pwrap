package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.backends.nixos.FileVerifier;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Command/authority contracts from test_file_authority.py; real cap0 execution is in NativeChecks. */
@EnabledInNativeImage
class FileAuthorityTest {
    
    final Path root, log;
    final PlatformConfig config;
    
    FileAuthorityTest(@TempDir Path root) throws IOException {
        this.root = root;
        log = root.resolve("argv");
        Path setpriv = script(root.resolve("setpriv"), "printf '%s\\n' \"$@\" > " + quote(log) + "\n");
        JsonObject raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("setpriv", setpriv.toString());
        config = new PlatformConfig(raw);
    }
    
    @Test void test_app_uses_the_shared_validators_and_dac_checker() throws IOException {
        Path source = Path.of("home/code/src/main/java/ru/pukpukov/pwrap");
        assertTrue(Files.readString(source.resolve("Launch.java")).contains("FileAuthority.prepare("));
        assertTrue(Files.readString(source.resolve("backends/nixos/Sandbox.java")).contains("FileVerifier.preflight("));
    }
    
    @Test void test_preflight_executes_file_authority_not_application_entry() throws Exception {
        Path file = Files.writeString(root.resolve("grant"), "data");
        var grants = List.of(new FileAuthority.Grant(file.toString(), "/data", false));
        try (var pins = new FileAuthority.Pins(grants, FileAuthority.validator("/session-home", List.of()))) {
            FileVerifier.preflight(grants, config, pins.fds());
            var argv = Files.readAllLines(log);
            assertEquals(List.of(
                "--bounding-set=-all", "--inh-caps=-all", "--ambient-caps=-all", "--clear-groups",
                "--no-new-privs", config.entry, "_file-authority", "_preflight-pins"
            ), argv.subList(0, 8));
            assertEquals(Json.encode(grants).toString(), argv.get(8));
            assertEquals(Json.encode(List.of(pins.fds().getFirst().n)).toString(), argv.get(9));
        }
    }
    
    @Test void test_shared_validation_uses_explicit_home_and_exclusions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FileAuthority.validate("/session-home", "/session-home"));
        assertThrows(IllegalArgumentException.class, () -> FileAuthority.validateWrite(
        "/session-home/control/child", List.of("~/control"), "/session-home"));
        for (String target : List.of("/", "/etc/shadow", "/run/systemd/private", "/nix/store"))
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.validate(target, "/session-home"));
        FileAuthority.validator("/session-home", List.of("~/control")).check(root.toString(), false);
    }
    
    @Test void test_conflicting_missing_grants_fail_before_persist_creation() {
        Path parent = root.resolve("not-created");
        var files = new Policy.FileAccess(
            List.of(parent.resolve("protected").toString()), List.of(),
            List.of(parent.toString()), List.of(), List.of(), null
        );
        assertThrows(IllegalArgumentException.class, () -> FileAuthority.prepare(files, "/session-home", FileVerifier.mkdir(config)));
        assertFalse(Files.exists(parent));
        assertFalse(Files.exists(log));
    }
    
    @Test void test_pin_preflight_passes_only_grant_descriptors_to_capability_free_child() throws Exception {
        Path file = Files.writeString(root.resolve("grant"), "data"), inherited = root.resolve("inherited");
        Path probe = script(root.resolve("probe"),
            "for fd in /proc/self/fd/*; do " + tool("readlink") + " /proc/$$/fd/${fd##*/} || :; done > " + quote(inherited) + "\n"
        );
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("setpriv", probe.toString());
        PlatformConfig tools = new PlatformConfig(raw);
        try (var unrelated = FileAuthority.openPath(root.toString());
        var pins = new FileAuthority.Pins(List.of(new FileAuthority.Grant(file.toString(), "/data", false)), (p, w) -> {})) {
            FileVerifier.preflight(List.of(new FileAuthority.Grant(file.toString(), "/data", false)), tools, pins.fds());
            var descriptors = Files.readAllLines(inherited);
            assertTrue(descriptors.contains(file.toString()));
            assertFalse(descriptors.contains(root.toString()));
            assertEquals(file.toString(), FileAuthority.physical(pins.fds().getFirst()));
            assertEquals(root.toString(), FileAuthority.physical(unrelated));
        }
    }
    
}