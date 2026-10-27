package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.backends.nixos.Desktop;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.*;

class PolicyTest {
    
    final Path root;
    PolicyTest(@TempDir Path root) { this.root = root; }
    
    @Test void test_local_mode_requires_an_explicit_endpoint_class() {
        for (JsonObject extra : List.of(obj(), obj("allowLAN", false), obj("allowLocalhost", false))) {
            JsonObject p = obj("command", List.of("/app"), "network", "local");
            extra.entrySet().forEach(e -> p.add(e.getKey(), e.getValue()));
            assertTrue(String.valueOf(assertThrows(IllegalArgumentException.class, () -> Policy.read(p)).getMessage()).contains("localhost or LAN"));
        }
        for (String field : List.of("allowLAN", "allowLocalhost")) Policy.read(obj("command", List.of("/app"), "network", "local", field, true));
    }
    
    @Test void test_defaults() {
        Policy.read(obj("command", List.of("/nix/store/example/bin/true")));
        Policy.read(obj("command", List.of("app"), "immutableData", List.of("/trusted/selected-alias")));
    }
    
    @Test void test_fail_closed() {
        for (JsonObject update : List.of(
            obj("netwrok", "host"), obj("schemaVersion", 2), obj("desktop", "false"), obj("command", List.of()),
            obj("command", "sh"), obj("network", "offline"), obj("tasksMax", true), obj("tasksMax", 0),
            obj("memoryMax", "4G\nProtectHome=no"), obj("environment", obj("BAD NAME", "x")), obj("environment", obj("OK", 1)),
            obj("network", "none", "allowLocalhost", true), obj("network", "local", "providers", obj("uri", obj("enable", true))),
            obj("network", "local", "dbusTalk", List.of("*"))
        )) {
            JsonObject p = obj("command", List.of("true"));
            update.entrySet().forEach(e -> p.add(e.getKey(), e.getValue()));
            assertThrows(IllegalArgumentException.class, () -> Policy.read(p), text(update));
        }
    }
    
    @Test void test_platform_network_configuration_is_an_object() {
        Policy.read(obj("command", List.of("app"), "networkConfig", obj("localDeniedPorts", List.of(2080))));
        for (@Nullable Object value : Arrays.asList(null, List.of(), "auto", true))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj("command", List.of("app"), "networkConfig", value)));
    }
    
    @Test void test_audio_requires_explicit_typed_provider() {
        for (JsonObject update : List.of(
            obj("audio", true), obj("providers", true), obj("providers", obj("sound", "nixos")),
            obj("providers", obj("audio", true)), obj("providers", obj("audio", "auto")),
            obj("providers", obj("audio", List.of())), obj("audio", true, "providers", obj("audio", null))
        )) {
            JsonObject p = obj("command", List.of("app"));
            update.entrySet().forEach(e -> p.add(e.getKey(), e.getValue()));
            assertThrows(IllegalArgumentException.class, () -> Policy.read(p));
        }
        for (String selected : List.of("nixos", "direct")) for (boolean grant : List.of(false, true))
            Policy.read(obj("command", List.of("app"), "audio", grant, "providers", obj("audio", selected)));
        Policy.read(obj("command", List.of("app"), "providers", obj("audio", null)));
    }
    
    @Test void test_portal_capabilities_are_independent_and_reject_old_broker_providers() {
        for (String field : List.of("uri", "openFile")) {
            for (boolean grant : List.of(false, true)) {
                var p = Policy.read(obj("command", List.of("app"), "providers", obj(field, obj("enable", grant))));
                assertEquals(grant, p.integration().opens().enabled());
            }
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                "command", List.of("app"), "network", "local", "allowLocalhost", true,
                "providers", obj(field, obj("enable", true))
            )));
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                "command", List.of("app"), "execution", "direct", "home", "/root",
                "providers", obj(field, obj("enable", true))
            )));
            for (Object invalid : List.of("true", 1, List.of()))
                assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                    "command", List.of("app"), "providers", obj(field, obj("enable", invalid))
                )));
        }
        for (String provider : List.of("webBrowser", "uris")) for (Object selected : List.of("broker", "direct", true))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj("command", List.of("app"), "providers", obj(provider, selected))));
        assertFalse(Policy.read(obj("command", List.of("app"))).integration().bus());
    }
    
    @Test void test_forbidden_write_policy_types() {
        Policy.read(obj("command", List.of("app"), "forbiddenWrites", List.of("/trusted/controller")));
        for (Object value : List.of("/trusted/controller", List.of(42), List.of(""), List.of("bad\0path")))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj("command", List.of("app"), "forbiddenWrites", value)));
    }
    
    @Test void test_forbidden_writes_resolve_leaf_and_parent_symlinks() throws Exception {
        Path controller = Files.createDirectory(root.resolve("controller")), alias = Files.createSymbolicLink(root.resolve("app-data"), controller);
        for (Path path : List.of(alias, alias.resolve("new-profile"), controller, root))
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.validateWrite(path.toString(), List.of(controller.toString()), "/root"));
        FileAuthority.validateWrite(root.resolve("controller-other").toString(), List.of(controller.toString()), "/root");
    }
    
    @Test void test_forbidden_writes_recheck_changed_link_after_configuration() throws Exception {
        Path pub = Files.createDirectory(root.resolve("public"));
        Path controller = Files.createDirectory(root.resolve("controller"));
        Path alias = Files.createSymbolicLink(root.resolve("app-data"), pub);
        FileAuthority.validateWrite(alias.toString(), List.of(controller.toString()), "/root");
        Files.delete(alias);
        Files.createSymbolicLink(alias, controller);
        assertThrows(IllegalArgumentException.class, () -> FileAuthority.validateWrite(alias.toString(), List.of(controller.toString()), "/root"));
    }
    
    @Test void test_broad_grants() {
        for (String path : List.of("/", "/home", "/nix/store", "/run/systemd/private", "/etc/shadow"))
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.validate(path, "/root"));
    }
    
    @Test void test_downloads_is_an_explicit_created_grant_and_null_grants_nothing() {
        var policy = Policy.read(obj("command", List.of("app"), "downloads", "~/Загрузки/app"));
        assertEquals(List.of("~/Загрузки/app"), policy.files().persist());
        assertEquals(policy.files(), policy.withFiles(policy.files()).files());
        assertTrue(Policy.read(obj("command", List.of("app"), "downloads", null)).files().persist().isEmpty());
        for (String path : List.of("relative", "", "/line\nbreak", "/line\rbreak"))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj("command", List.of("app"), "downloads", path)));
        assertEquals("XDG_DOWNLOAD_DIR=\"/Загрузки/app\"\n", Desktop.userDirectories("/Загрузки/app"));
        assertEquals("XDG_DOWNLOAD_DIR=\"/a\\$b\\`c\\\"d\\\\e\"\n", Desktop.userDirectories("/a$b`c\"d\\e"));
    }
    
}