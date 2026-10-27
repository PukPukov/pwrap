package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.Json.required;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Direct port of test_cli.py: parse + normalize, without invoking a host launcher. */
class CliTest {
    
    final Path root;
    
    CliTest(@TempDir Path root) { this.root = root; }
    
    private Config config() { return RuntimeFixture.config(root, obj()); }
    
    private Policy launch(Config config, String... args) throws Exception { return Cli.policy(Cli.parse(List.of(args)), config); }
    
    private Path manifest(JsonObject value) throws IOException {
        Path path = root.resolve("app.json"); Files.writeString(path, value.toString()); return path;
    }
    
    private void register(Path path) throws IOException { Files.writeString(root.resolve("profiles.json"), obj("app", path.toString()).toString()); }
    
    @Test void test_gpu_and_resource_limits_are_independent_from_desktop() throws Exception {
        Policy compute = launch(config(), "run", "--gpu", "--tasks-max", "37", "--", "app");
        assertTrue(compute.integration().gpu());
        assertFalse(compute.integration().desktop());
        assertEquals(37, required(compute.limits(), "Expected fixture value").tasksMax());
        Policy software = launch(config(), "run", "--desktop", "--no-gpu", "--", "app");
        assertTrue(software.integration().desktop());
        assertFalse(software.integration().gpu());
        for (String value : List.of("0", "-1"))
            assertThrows(IllegalArgumentException.class, () -> launch(config(), "run", "--tasks-max", value, "--", "app"));
    }
    
    @Test void test_exact_dbus_operation_flags_use_the_public_record_schema() throws Exception {
        JsonObject operation = obj(
            "destination", "org.example.App", "interface", "org.example.Interface", "path", "/org/example/App", "members", List.of("Open")
        );
        Policy policy = launch(
            config(), "run", "--dbus-own", "org.example.Client", "--dbus-call", operation.toString(),
            "--dbus-broadcast", operation.toString(), "--", "app"
        );
        Dbus dbus = policy.integration().dbus();
        assertEquals(List.of("org.example.Client"), dbus.own());
        assertEquals(List.of(Json.decode(operation, Dbus.Operation.class)), dbus.calls());
        assertEquals(dbus.calls(), dbus.broadcasts());
        for (String value : List.of("{}", "not-json"))
            assertThrows(RuntimeException.class, () -> launch(config(), "run", "--dbus-call", value, "--", "app"));
    }
    
    @Test void test_explicit_false_profile_overlay_cannot_be_mistaken_for_activation_only() throws Exception {
        Path path = manifest(obj("name", "app", "command", List.of("app"), "gpu", true, "sharedTmp", true));
        Policy policy = launch(config(), "profile", path.toString(), "--no-gpu", "--no-shared-tmp", "--", "argument");
        assertFalse(policy.integration().gpu());
        assertFalse(policy.integration().sharedTmp());
        assertNull(policy.invocation().activationArguments());
        assertTrue(launch(config(), "profile", path.toString(), "--shared-tmp").integration().sharedTmp());
        assertThrows(IllegalArgumentException.class, () -> launch(config(), "run", "--shared-tmp", "--", "app"));
    }
    
    @Test void test_local_cli_requires_an_explicit_endpoint_grant() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> launch(config(), "run", "--net", "local", "--", "app"));
        Policy policy = launch(config(), "run", "--net", "local", "--allow-lan", "--", "app");
        assertTrue(policy.network().allowLAN());
        assertFalse(policy.network().allowLocalhost());
    }
    
    @Test void test_start_memory_is_not_ignored_or_forwarded() throws Exception {
        register(manifest(obj("name", "app", "command", List.of("test-app"), "memoryMax", "4G")));
        Policy policy = launch(config(), "start", "app", "--memory", "1G", "--", "argument");
        assertEquals("1G", required(policy.limits(), "Expected fixture value").memoryMax());
        assertNull(policy.invocation().activationArguments());
    }
    
    @Test void test_profile_and_registered_profile_share_option_semantics() throws Exception {
        Path path = manifest(obj("name", "app", "command", List.of("test-app"), "persist", List.of("~/state")));
        register(path);
        String[] extra = {"--read", "/input", "--immutable-data", "/trusted/resource", "--net", "internet", "--env", "TEST=value", "--", "argument"};
        Policy registered = launch(config(), Commands.join(List.of("start", "app"), List.of(extra)).toArray(String[]::new));
        Policy direct = launch(config(), Commands.join(List.of("profile", path.toString()), List.of(extra)).toArray(String[]::new));
        assertEquals(registered, direct);
        assertEquals(List.of("/input"), direct.files().readOnly());
        assertEquals(List.of("/trusted/resource"), direct.files().immutableData());
        assertEquals(List.of("test-app", "argument"), direct.command());
    }
    
    @Test void test_embedded_registry_works_without_host_module() throws Exception {
        Path path = manifest(obj("name", "app", "command", List.of("test-app")));
        Config config = RuntimeFixture.config(root.resolve("not-installed"), obj("profiles", obj("app", path.toString())));
        assertEquals(List.of("test-app"), launch(config, "start", "app").command());
    }
    
    @Test void test_provider_overlay_shared_by_run_profile_and_registry() throws Exception {
        Path path = manifest(obj("command", List.of("app"), "audio", true, "providers", obj("audio", "nixos")));
        register(path);
        Policy profile = launch(config(), "profile", path.toString(), "--provider", "audio=direct", "--", "argument");
        assertEquals(profile, launch(config(), "start", "app", "--provider", "audio=direct", "--", "argument"));
        assertEquals(Policy.Audio.DIRECT, profile.integration().audioProvider());
        assertNull(profile.invocation().activationArguments());
        assertEquals(
            Policy.Audio.DIRECT,
            launch(config(), "run", "--audio", "--provider", "audio=direct", "--", "app", "argument").integration().audioProvider()
        );
    }
    
    @Test void test_cli_invalid_provider_or_missing_audio_provider_is_error() {
        for (List<String> flags : List.<List<String>>of(
            List.of("--provider", "audio=auto"), List.of("--provider", "video=nixos"), List.of("--provider", "audio"),
            List.of("--provider", "audio=true"), List.of("--audio")
        ))
            assertThrows(RuntimeException.class, () -> launch(
                config(), Commands.join(List.of("run"), flags, List.of("--", "app")).toArray(String[]::new)
            ));
    }
    
    @Test void test_portal_capabilities_do_not_require_a_custom_provider() throws Exception {
        Policy policy = launch(config(), "run", "--open-uri", "tg", "--", "app");
        assertEquals(List.of("tg"), policy.integration().opens().uri().schemes());
        assertFalse(policy.integration().opens().openFile().enable());
        assertTrue(launch(config(), "run", "--open-web", "--", "app").integration().opens().uri().enable());
        assertTrue(launch(config(), "run", "--open-files", "--", "app").integration().opens().openFile().enable());
        for (String old : List.of("webBrowser=broker", "uris=broker"))
            assertThrows(Cli.Usage.class, () -> launch(config(), "run", "--provider", old, "--", "app"));
    }
    
    @Test void test_cli_ask_configuration_is_not_an_implicit_open_permission() throws Exception {
        var dormant = launch(config(), "run", "--no-file-ask", "--uri-ask", "--", "app").integration().opens();
        assertFalse(dormant.enabled());
        assertFalse(dormant.openFile().ask());
        assertTrue(dormant.uri().ask());
        var enabled = launch(
            config(), "run", "--open-files", "--no-file-ask", "--open-web", "--open-uri", "tg", "--uri-ask", "--", "app"
        ).integration().opens();
        assertTrue(enabled.openFile().enable());
        assertFalse(enabled.openFile().ask());
        assertEquals(List.of("http", "https", "tg"), enabled.uri().schemes());
        assertTrue(enabled.uri().ask());
    }
    
}