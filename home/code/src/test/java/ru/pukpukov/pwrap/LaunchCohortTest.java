package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import ru.pukpukov.pwrap.backends.nixos.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;

/** test_launch_cohort.py: full prepare path with deterministic resource discovery and real leases. */
@EnabledInNativeImage
class LaunchCohortTest {

    final Path root;
    final Instances instances;
    final JsonObject descriptor, manifest;

    Session.Environment session = new Session.Environment(Map.of("HOME", "/home/test", "XDG_RUNTIME_DIR", "/run/user/0", "TERM", "first"));
    Desktop.Appearance appearance = new Desktop.Appearance(
        Map.of("/selected-font", "/immutable/font"), List.of(new Resources.Binding("/immutable/icons", "/icon-alias")),
        Map.of("GTK_THEME", "First"), List.of(), List.of(), "theme=first"
    );
    Graphics.Snapshot graphics = new Graphics.Snapshot(List.of(), List.of(), Map.of(), List.of(), List.of());
    int captures;

    LaunchCohortTest(@TempDir Path root) throws Exception {
        this.root = root;
        instances = SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
        String executable = Commands.executable("true"); Path closure = Files.writeString(root.resolve("closure"), NixStorage.root(executable) + "\n");
        descriptor = descriptor(root.resolve("absent"), obj("desktop", obj()));
        descriptor.addProperty("baseClosure", closure.toString());
        descriptor.addProperty("nixStore", script(root.resolve("nix-store"), "exit 0\n").toString());
        manifest = obj(
            "name", "app", "command", List.of(executable), "manifest", "/nix/store/profile.json",
            "desktop", true, "gpu", true, "sharedTmp", true
        );
    }
    
    private Launch.Prepared prepare(Lifetime lifetime, JsonObject input, Config config) throws Exception {
        return Launch.prepare(Policy.read(input), config, lifetime, session, instances, (p, c, s) -> {
            captures++;
            return new Launch.Capture(s, appearance, graphics);
        });
    }

    private Launch.Prepared prepare(Lifetime lifetime) throws Exception { return prepare(lifetime, manifest, new Config(descriptor)); }

    private void publish(Launch.Prepared p) throws Exception {
        Files.createDirectories(root.resolve("groups/pwrap-detached.service"));
        Files.writeString(root.resolve("groups/pwrap-detached.service/cgroup.events"), "populated 1\n");
        instances.register(
            "app", "pwrap-detached.service", "fingerprint", p.session().values(),
            Map.of(Launch.CODEC.key(), Json.encode(p.launchSnapshot()).getAsJsonObject()), true
        );
    }

    @Test void test_theme_fonts_and_gpu_changes_do_not_break_relaunch_or_grant_new_resources() throws Exception {
        String scratch;
        try (var first = new Lifetime()) {
            var old = prepare(first); scratch = Json.required(old.scratch(), "Shared scratch");
            appearance = new Desktop.Appearance(
                Map.of("/new-font", "/new/authority"), List.of(), Map.of("GTK_THEME", "New"), List.of(), List.of(), "theme=new"
            );
            graphics = new Graphics.Snapshot(
                List.of(), List.of(), Map.of("devices/gpu", new Graphics.Entry("new", null, false)), List.of(), List.of()
            );
            session = new Session.Environment(Map.of("HOME", "/home/test", "XDG_RUNTIME_DIR", "/run/user/0", "TERM", "later"));
            try (var second = new Lifetime()) {
                JsonObject input = manifest.deepCopy();
                input.add("command", Json.encode(List.of(old.policy().command().getFirst(), "https://example.invalid/")));
                var relaunch = prepare(second, input, new Config(descriptor));
                assertEquals(old.desktop(), relaunch.desktop());
                assertEquals(old.graphics(), relaunch.graphics());
                assertEquals(old.scratch(), relaunch.scratch());
                assertEquals(old.environment(), relaunch.environment());
                assertEquals(old.launchSnapshot(), relaunch.launchSnapshot());
                assertEquals(1, captures);
            }
        }
        assertFalse(Files.exists(Path.of(scratch)));
        try (var fresh = new Lifetime()) {
            var updated = prepare(fresh);
            assertEquals("theme=new", updated.desktop().settings());
            assertEquals(graphics, updated.graphics());
            assertNotEquals(scratch, updated.scratch());
            assertEquals(2, captures);
        }
    }

    @Test void test_every_explicit_permission_helper_and_selected_metadata_change_is_rejected() throws Exception {
        try (var first = new Lifetime()) {
            prepare(first);
            for (JsonObject update : List.of(
                obj("writable", List.of("/new-grant")), obj("network", "host"), obj("gpu", false),
                obj("environment", obj("GTK_THEME", "ExplicitChange")), obj("sharedTmp", false), obj("command", List.of(Commands.executable("sh")))
            )) {
                JsonObject input = manifest.deepCopy(); update.entrySet().forEach(e -> input.add(e.getKey(), e.getValue()));
                assertThrows(IllegalArgumentException.class, () -> { try (var second = new Lifetime()) {
                    prepare(second, input, new Config(descriptor));
                } }, input.toString());
            }
            for (String field : List.of("scope", "metadata")) {
                JsonObject changed = descriptor.deepCopy();
                changed.add(field, field.equals("scope") ? new JsonPrimitive("/different-helper") : obj("desktop", obj("files", obj("/new", "/new"))));
                assertThrows(IllegalArgumentException.class, () -> { try (var second = new Lifetime()) {
                    prepare(second, manifest, new Config(changed));
                } });
            }
            JsonObject unrelated = descriptor.deepCopy();
            unrelated.getAsJsonObject("metadata").add("profiles", obj("unrelated", "/unused"));
            try (var second = new Lifetime()) { prepare(second, manifest, new Config(unrelated)); }
            assertEquals(1, captures);
        }
    }

    @Test void test_detached_child_record_preserves_discovery_after_outer_exit() throws Exception {
        Launch.Prepared prepared;
        try (var outer = new Lifetime()) { prepared = prepare(outer); publish(prepared); }
        appearance = Desktop.Appearance.empty();
        try (var relaunch = new Lifetime()) {
            var reused = prepare(relaunch);
            assertEquals(prepared.desktop(), reused.desktop());
            assertEquals(1, captures);
        }
        Path scratch = Path.of(Json.required(prepared.scratch(), "Shared scratch"));
        assertTrue(Files.exists(scratch));
        instances.release("app", "pwrap-detached.service");
        assertFalse(Files.exists(scratch));
    }
    
}