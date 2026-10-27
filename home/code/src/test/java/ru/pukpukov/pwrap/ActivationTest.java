package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nixos.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;

/** test_activation.py: real protected records/process identities; busctl is a shell fixture. */
@EnabledInNativeImage
class ActivationTest {
    
    final Path root, calls, owner, credentials;
    final Instances instances;
    final Config config;
    final Launch.Prepared prepared;
    final Network.Boundary boundary;
    
    ActivationTest(@TempDir Path root) throws Exception {
        this.root = root;
        calls = root.resolve("calls");
        owner = Files.writeString(root.resolve("owner"), "{\"data\":[\":1.42\"]}\n");
        credentials = Files.writeString(root.resolve("credentials"), "{\"data\":[42]}\n");
        Path bus = script(
            root.resolve("busctl"), """
            printf '%s\\n' "$*" >> %s
            case "$*" in
              *GetNameOwner*) while IFS= read -r line; do printf '%%s\\n' "$line"; done < %s; [ -e %s ] && exit 1; exit 0;;
              *GetConnectionUnixProcessID*) while IFS= read -r line; do printf '%%s\\n' "$line"; done < %s; [ -e %s ] && exit 1; exit 0;;
            esac
            """.replace("printf '%s", "printf '%%s").formatted(
                quote(calls), quote(owner), quote(root.resolve("no-owner")), quote(credentials), quote(root.resolve("lost-owner"))
            )
        );
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("busctl", bus.toString());
        config = new Config(raw);
        prepared = prepared(Policy.read(obj(
            "name", "app", "command", List.of("/app", "tg://open"),
            "dbusActivation", obj("name", "org.example.App", "path", "/org/example/App"),
            "activationArguments", List.of("tg://open")
        )), new Session.Environment(Map.of("HOME", "/session-home", "XDG_RUNTIME_DIR", "/session-run")));
        boundary = new Network.Boundary(
            new Network.Request(1, config.platform.entry, prepared.policy().network(), config.platform.network()),
            new Network.Capture(List.of(), "", List.of())
        );
        instances = SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
        Path group = Files.createDirectories(root.resolve("groups/pwrap-first.service"));
        Files.writeString(group.resolve("cgroup.events"), "populated 1\n");
        Files.createDirectories(root.resolve("proc/42"));
        Files.writeString(root.resolve("proc/42/cgroup"), "0::/system.slice/pwrap-first.service/child\n");
        instances.register(
            "app", "pwrap-first.service", Launch.fingerprint(prepared.authority(boundary, config.platform.identity)),
            prepared.session().values(), Map.of(), true
        );
    }
    
    private int run(Launch.Prepared value) throws Exception {
        return Platform.dispatch(value, boundary, (p, b) -> Activation.activate(p, b, config, instances), () -> {
            Files.writeString(root.resolve("new-unit"), "started");
            return 9;
        });
    }
    
    private List<String> calls() throws IOException { return Files.exists(calls) ? Files.readAllLines(calls) : List.of(); }
    
    @Test void test_matching_live_peer_receives_uri_without_new_unit_firewall_or_payload() throws Exception {
        assertEquals(0, run(prepared));
        assertFalse(Files.exists(root.resolve("new-unit")));
        var lines = calls();
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).endsWith(" s :1.42"));
        String handoff = lines.getLast();
        for (String expected : List.of(
            "--address=unix:path=/session-run/bus", "--auto-start=no", "call :1.42", "org.freedesktop.Application", "tg://open"
        )) assertTrue(handoff.contains(expected), handoff);
        assertFalse(handoff.contains("org.example.App"));
        try (var files = Files.list(root.resolve("instances/app"))) { assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count()); }
    }
    
    @Test void test_changed_effective_authority_fails_before_contacting_peer() throws Exception {
        var changed = new Launch.Prepared(
            prepared.policy(), prepared.session(), prepared.desktop(), null, prepared.audio(), List.of(), prepared.runtime(),
            Map.of("CHANGED", "1"), prepared.cwd(), null, null
        );
        assertThrows(IllegalArgumentException.class, () -> run(changed));
        assertTrue(calls().isEmpty());
        assertFalse(Files.exists(root.resolve("new-unit")));
    }
    
    @Test void test_no_owner_launches_normally_but_unconfined_owner_is_not_an_activation_target() throws Exception {
        Files.writeString(root.resolve("no-owner"), "");
        assertEquals(9, run(prepared));
        Files.delete(root.resolve("no-owner"));
        Files.delete(root.resolve("new-unit"));
        Files.delete(calls);
        Files.writeString(root.resolve("proc/42/cgroup"), "0::/user.slice/unconfined\n");
        assertThrows(IllegalArgumentException.class, () -> run(prepared));
        assertEquals(2, calls().size());
        assertFalse(Files.exists(root.resolve("new-unit")));
    }
    
    @Test void test_profiles_without_declared_standard_activation_use_normal_launch() throws Exception {
        for (String field : List.of("dbusActivation", "activationArguments")) {
            JsonObject input = obj(
                "command", List.of("/app"), "dbusActivation", obj("name", "org.example.App", "path", "/org/example/App"),
                "activationArguments", List.of()
            );
            input.remove(field);
            assertEquals(9, run(prepared(Policy.read(input), prepared.session())));
        }
        assertTrue(calls().isEmpty());
    }
    
    @Test void test_invalid_unique_owner_fails_before_pid_or_application_calls() throws Exception {
        for (JsonElement value : JsonParser.parseString("[\"org.example.Other\",\"\",42,\":not-a-unique-name\"]").getAsJsonArray()) {
            Files.writeString(owner, obj("data", List.of(value)).toString() + "\n");
            assertThrows(IllegalArgumentException.class, () -> run(prepared));
            assertEquals(1, calls().size());
            Files.delete(calls);
        }
        assertFalse(Files.exists(root.resolve("new-unit")));
    }
    
    @Test void test_owner_disappearing_after_name_resolution_does_not_autostart_or_fall_back() throws Exception {
        Files.writeString(root.resolve("lost-owner"), "");
        assertThrows(IOException.class, () -> run(prepared));
        assertEquals(2, calls().size());
        assertFalse(Files.exists(root.resolve("new-unit")));
    }
    
    @Test void test_dbus_activation_uses_captured_session_not_systemd_environment() throws Exception {
        assertTrue(Activation.activate(prepared, boundary, config, instances));
        assertTrue(calls().stream().allMatch(s -> s.contains("--address=unix:path=/session-run/bus")));
    }
    
    @Test void test_missing_captured_dbus_context_fails_closed() {
        var missing = prepared(prepared.policy(), new Session.Environment(Map.of("HOME", "/session-home")));
        assertThrows(IllegalArgumentException.class, () -> Activation.activate(missing, boundary, config, instances));
    }
    
}