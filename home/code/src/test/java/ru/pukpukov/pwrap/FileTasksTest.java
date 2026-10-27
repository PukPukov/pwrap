package ru.pukpukov.pwrap;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.SystemdInstances;
import ru.pukpukov.pwrap.backends.nixos.Sandbox;
import ru.pukpukov.pwrap.backends.nixos.Desktop;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;

/** FileTaskTests: full preparation/pinning, plus real CLI/cap0 cases in NativeChecks. */
@EnabledInNativeImage
class FileTasksTest {
    
    final Path root, allowed, closure, marker;
    final JsonObject descriptor, manifest;
    final Instances instances;
    
    FileTasksTest(@TempDir Path root) throws Exception {
        this.root = root;
        allowed = Files.createDirectory(root.resolve("profile"));
        marker = root.resolve("payload");
        String executable = Commands.executable("true");
        closure = Files.writeString(root.resolve("closure"), NixStorage.root(executable) + "\n");
        descriptor = descriptor(root.resolve("absent"), obj());
        descriptor.addProperty("baseClosure", closure.toString());
        descriptor.addProperty("filter", "/dev/null");
        descriptor.addProperty("bwrap", script(root.resolve("bwrap"), "printf started > " + quote(marker) + "\n").toString());
        manifest = obj("execution", "direct", "home", root.resolve("home").toString(), "command", List.of(executable), "closure", closure.toString(),
            "writable", List.of(allowed.toString()), "inheritEnvironment", List.of("VERBOSE")
        );
        instances = SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
    }
    
    private Launch.Prepared prepare(JsonObject input, Lifetime lifetime) throws Exception {
        return Launch.prepare(Policy.read(input), new Config(descriptor), lifetime,
            new Session.Environment(Map.of("HOME", root.resolve("home").toString())), instances,
            (p, c, s) -> new Launch.Capture(s, Desktop.Appearance.empty(), null)
        );
    }
    
    private JsonObject changed(JsonObject update) {
        JsonObject input = manifest.deepCopy();
        update.entrySet().forEach(e -> input.add(e.getKey(), e.getValue()));
        return input;
    }
    
    @Test void test_closed_descriptor_rejects_unknown_or_wrong_types() {
        for (JsonObject update : List.of(
            obj("schemaVersion", true), obj("writable", "a"), obj("readOnly", List.of("/")),
            obj("writable", List.of(root.resolve("home").toString())), obj("inheritEnvironment", List.of("HOME;exit")),
            obj("command", List.of()), obj("home", "relative"), obj("tools", obj("bwrap", "/tool")), obj("typo", true)
        )) assertThrows(IllegalArgumentException.class,
            () -> { try (var lifetime = new Lifetime()) { prepare(changed(update), lifetime); } },
            update.toString()
        );
        assertFalse(Files.exists(marker));
    }
    
    @Test void test_direct_rejects_network_brokers_and_limits_instead_of_ignoring_them() {
        for (JsonObject extra : List.of(
            obj("network", "host"), obj("network", "internet"), obj("desktop", true), obj("gpu", true),
            obj("dbusOwn", List.of("org.example.App")), obj("memoryMax", "1G"), obj("tasksMax", 10)
        ))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(changed(extra)), extra.toString());
    }
    
    @Test void test_task_and_application_use_the_same_forbidden_write_validation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            try (var lifetime = new Lifetime()) {
                prepare(changed(obj("forbiddenWrites", List.of(allowed.toString()))), lifetime);
            }
        });
        var validate = FileAuthority.validator(root.resolve("home").toString(), List.of(allowed.toString()));
        assertThrows(IllegalArgumentException.class, () -> validate.check(allowed.toString(), true));
        validate.check(allowed.toString(), false);
    }
    
    @Test void test_immutable_controller_alias_is_resolved_by_shared_storage_not_prefix_parse() throws Exception {
        Path controller = Files.createDirectory(root.resolve("trusted-controller"));
        Path alias = Files.createSymbolicLink(controller.resolve("selected"), Path.of(Commands.executable("true")));
        try (var lifetime = new Lifetime()) {
            var p = prepare(changed(obj("immutableData", List.of(alias.toString()))), lifetime);
            assertTrue(p.runtime().files().contains(new Resources.Binding(Commands.executable("true"), alias.toString())));
            assertFalse(p.runtime().paths().contains(controller.toString()));
        }
    }
    
    @Test void test_initial_alias_is_never_followed_even_without_forbidden_paths() throws Exception {
        Path privateDir = Files.createDirectory(root.resolve("private")), alias = Files.createSymbolicLink(root.resolve("alias"), privateDir);
        for (String key : List.of("writable", "readOnly")) {
            try (var lifetime = new Lifetime()) {
                var p = prepare(changed(obj(key, List.of(alias.toString()))), lifetime);
                assertThrows(IOException.class, () -> Payload.run(
                    p, new Config(descriptor), null, new Sandbox(new Config(descriptor).platform, new Lifetime(root))
                ));
            }
            assertFalse(Files.exists(marker));
            assertFalse(Files.exists(privateDir.resolve("missing")));
        }
    }
    
    @Test void test_unknown_closure_members_fail_not_broad_mount_fallback() throws Exception {
        Files.writeString(closure, "/\n");
        assertThrows(IllegalArgumentException.class, () -> {
            try (var lifetime = new Lifetime()) {
                prepare(manifest, lifetime);
            }
        });
        assertFalse(Files.exists(marker));
    }
    
}