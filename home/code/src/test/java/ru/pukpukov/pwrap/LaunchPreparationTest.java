package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import ru.pukpukov.pwrap.backends.nixos.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;

/** Remaining preparation cases from test_providers/discovery_context/graphics.py. */
@EnabledInNativeImage
class LaunchPreparationTest {

    final Path root, preflight, endpoint, helperLog;
    final JsonObject descriptor;
    final Instances instances;
    final ServerSocketChannel server;
    Session.Environment session;
    int captures;

    LaunchPreparationTest(@TempDir Path root) throws Exception {
        this.root = root;
        preflight = root.resolve("preflight"); endpoint = root.resolve("pulse"); helperLog = root.resolve("helpers");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX); server.bind(UnixDomainSocketAddress.of(endpoint));
        Path home = Files.createDirectory(root.resolve("host-home")), font = Files.writeString(root.resolve("fonts.conf"), "<fontconfig/>");
        session = new Session.Environment(Map.of(
            "HOME", home.toString(), "XDG_RUNTIME_DIR", root.toString(), "FONTCONFIG_FILE", font.toString(),
            "XDG_DATA_DIRS", root.resolve("share").toString(), "XDG_CONFIG_DIRS", root.resolve("etc").toString(),
            "LANG", "C.UTF-8"
        ));
        descriptor = descriptor(root.resolve("absent"), obj("providers", obj("audio", obj("nixos", obj("socketPath", endpoint.toString())))));
        Path closure = Files.writeString(root.resolve("closure"), NixStorage.root(Commands.executable("true")) + "\n");
        descriptor.addProperty("baseClosure", closure.toString());
        descriptor.addProperty("nixStore", script(root.resolve("nix-store"), "exit 0\n").toString());
        descriptor.addProperty("setpriv", script(root.resolve("setpriv"), "printf '%s\\n' \"$@\" >> " + quote(preflight) + "\n").toString());
        String probe = "printf '%s|%s|%s|%s|%s|%s\\n' \"$HOME\" \"$FONTCONFIG_FILE\" \"${LD_PRELOAD-unset}\" "
            + "\"${GCONV_PATH-unset}\" \"${GIO_EXTRA_MODULES-unset}\" \"$GTK_THEME\" >> " + quote(helperLog) + "\n";
        Path fc = script(root.resolve("tools/fc-list"), probe);
        script(root.resolve("tools/fc-conflist"), probe);
        descriptor.addProperty("fcList", fc.toString());
        descriptor.addProperty("gsettings", script(root.resolve("tools/gsettings"), probe).toString());
        instances = SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
    }

    @AfterEach void close() throws IOException { server.close(); }

    private JsonObject input(JsonObject extra) throws IOException {
        JsonObject value = obj("command", List.of(Commands.executable("true")));
        extra.entrySet().forEach(e -> value.add(e.getKey(), e.getValue()));
        return value;
    }

    private Launch.Prepared prepare(JsonObject extra, Lifetime lifetime) throws Exception {
        return Launch.prepare(Policy.read(input(extra)), new Config(descriptor), lifetime, session, instances, (p, c, s) -> {
            captures++;
            return new Launch.Capture(
                s,
                p.integration().desktop() ? Desktop.discover(c.platform, s, NixStorage.PROVENANCE) : Desktop.Appearance.empty(),
                p.integration().gpu() ? new Graphics.Snapshot(List.of(), List.of(), Map.of(), List.of(), List.of()) : null
            );
        });
    }

    @Test void test_audio_false_selected_provider_has_no_grants_or_side_effects() throws Exception {
        try (var lifetime = new Lifetime()) { var p = prepare(obj("providers", obj("audio", "nixos")), lifetime);
            assertTrue(p.audio().files().isEmpty());
            assertTrue(p.grants().isEmpty());
            assertFalse(Files.exists(preflight));
        }
    }

    @Test void test_nixos_payload_cannot_keep_rejected_cookie_or_server_override() throws Exception {
        try (var lifetime = new Lifetime()) {
            var p = prepare(obj("audio", true, "providers", obj("audio", "nixos"), "environment",
            obj("PULSE_SERVER", "unix:/other", "PULSE_COOKIE", "/other")), lifetime);
            assertEquals("unix:" + endpoint, p.environment().get("PULSE_SERVER"));
            assertFalse(p.environment().containsKey("PULSE_COOKIE"));
        }
    }

    @Test void test_effective_audio_files_are_preflighted_not_general_run_grants() throws Exception {
        try (var lifetime = new Lifetime()) {
            var p = prepare(obj("audio", true, "providers", obj("audio", "nixos")), lifetime);
            assertTrue(p.grants().isEmpty());
            assertEquals(Map.of(endpoint.toString(), endpoint.toString()), p.audio().files());
            var argv = Files.readAllLines(preflight);
            assertEquals("_preflight", argv.get(7));
            assertEquals(Json.encode(List.of(new FileAuthority.Grant(endpoint.toString(), endpoint.toString(), false))).toString(), argv.get(8));
        }
    }

    @Test void test_gpu_discovery_independent_of_desktop() throws Exception {
        try (var lifetime = new Lifetime()) { var p = prepare(obj("gpu", true), lifetime);
            Json.require(p.graphics() != null, "GPU was not captured");
            assertEquals(Desktop.Appearance.empty(), p.desktop());
            assertEquals(1, captures);
            assertFalse(Files.exists(helperLog));
        }
    }

    @Test void test_named_relaunch_reuses_only_the_trusted_existing_session() throws Exception {
        try (var first = new Lifetime()) {
            var old = prepare(obj("name", "app"), first);
            var changed = new HashMap<>(session.values()); changed.put("LANG", "later"); session = new Session.Environment(changed);
            try (var second = new Lifetime()) { var p = prepare(obj("name", "app"), second);
                assertEquals(old.session(), p.session());
                assertEquals(old.environment(), p.environment());
                assertEquals(1, captures);
            }
        }
    }

    @Test void test_new_session_snapshot_is_allowlisted_and_recorded_session_is_strict() throws Exception {
        assertEquals(Set.of("HOME"), Session.capture(List.of("HOME")).values().keySet());
        var values = new HashMap<>(session.values());
        values.put("HOST_SECRET", "must-not-leak"); session = new Session.Environment(values);
        assertThrows(IllegalArgumentException.class, () -> { try (var lifetime = new Lifetime()) { prepare(obj(), lifetime); } });
    }

    @Test void test_prepare_separates_payload_inputs_from_every_host_helper() throws Exception {
        descriptor.getAsJsonObject("metadata").add("desktop", obj("environment", obj(
            "GTK_THEME", "TrustedTheme", "LD_PRELOAD", "/supplement-loader",
            "GIO_EXTRA_MODULES", "/supplement-modules", "GCONV_PATH", "/supplement-converters"
        )));
        Map<String, String> payload = new LinkedHashMap<>();
        for (String key : List.of("HOME", "FONTCONFIG_FILE", "LD_PRELOAD", "GCONV_PATH", "QT_PLUGIN_PATH", "XDG_DATA_DIRS"))
            payload.put(key, "/payload/" + key);
        try (var lifetime = new Lifetime()) {
            var p = prepare(obj("desktop", true, "gpu", false, "environment", payload), lifetime);
            assertEquals(session, p.session());
            for (var e : payload.entrySet()) assertEquals(e.getValue(), p.environment().get(e.getKey()));
            var helpers = Files.readAllLines(helperLog);
            assertEquals(4, helpers.size());
            for (String helper : helpers) {
                assertFalse(helper.contains("/payload/") || helper.contains("/supplement-"), helper);
                assertTrue(helper.startsWith(session.session().home() + "|"));
                assertTrue(helper.contains("|unset|unset|"));
                assertTrue(helper.endsWith("|TrustedTheme"));
            }
            assertTrue(p.desktop().files().containsKey(Json.required(session.values().get("FONTCONFIG_FILE"), "Fixture fontconfig")));
        }
    }

    @Test void test_context_projects_trusted_inputs_and_freezes_a_snapshot() throws Exception {
        Map<String, String> source = new HashMap<>(session.values());
        Session.Environment frozen = new Session.Environment(source);
        source.put("HOME", "/changed");
        assertEquals(session, frozen);
        assertThrows(UnsupportedOperationException.class, () -> frozen.values().put("HOME", "/changed"));
        descriptor.getAsJsonObject("metadata").add("desktop", obj("environment", obj(
            "LD_LIBRARY_PATH", "/supplement-loader", "GIO_EXTRA_MODULES", "/supplement-modules"
        )));
        Desktop.discover(new Config(descriptor).platform, frozen, NixStorage.PROVENANCE);
        assertTrue(Files.readAllLines(helperLog).stream().noneMatch(s -> s.contains("supplement")));
        assertEquals(session, frozen);
    }

    @Test void test_discovery_and_settings_require_an_explicit_context() {
        for (Map<String, String> env : List.<Map<String, String>>of(
            Map.of(), Map.of("HOME", "relative"), Map.of("HOME", "/ok", "XDG_RUNTIME_DIR", "relative")
        )) assertThrows(IllegalArgumentException.class, () -> new Session.Environment(env));
        // Both entrypoints accept Environment, not Map; a missing/wrong argument is a compile error.
        assertFalse(Files.exists(helperLog));
    }

    @Test void test_provider_and_effective_endpoint_change_instance_fingerprint() throws Exception {
        try (var lifetime = new Lifetime()) {
            var p = prepare(obj(), lifetime);
            var boundary = new Network.Boundary(
                new Network.Request(1, new Config(descriptor).platform.entry, p.policy().network(), new Config(descriptor).platform.network()),
                new Network.Capture(List.of(), "", List.of())
            );
            String fingerprint = Launch.fingerprint(p.authority(boundary, new Config(descriptor).platform.identity));
            for (JsonObject change : List.of(
                obj("environment", obj("TEST", "changed")),
                obj("audio", obj("files", obj("/other", "/other"), "environment", obj()))
            )) {
                JsonObject raw = Json.encode(p).getAsJsonObject();
                change.entrySet().forEach(e -> raw.add(e.getKey(), e.getValue()));
                var changed = Json.decode(raw, Launch.Prepared.class);
                assertNotEquals(fingerprint, Launch.fingerprint(changed.authority(boundary, new Config(descriptor).platform.identity)));
            }
        }
    }
}