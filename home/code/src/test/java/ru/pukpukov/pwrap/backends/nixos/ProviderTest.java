package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;

/** Direct provider/resource cases from test_providers.py; launch/cohort cases are separate. */
@EnabledInNativeImage
class ProviderTest {
    
    final Path root;
    Path endpoint;
    ServerSocketChannel server;
    Map<String, String> environment;
    PlatformConfig config;
    
    ProviderTest(@TempDir Path root) throws IOException {
        this.root = root;
        endpoint = root.resolve("fixed-pulse");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(endpoint));
        environment = Map.of("HOME", root.toString(), "XDG_RUNTIME_DIR", root.toString());
        config = RuntimeFixture.platform(root.resolve("absent"), obj(
            "providers", obj("audio", obj("nixos", obj("socketPath", endpoint.toString())))
        ));
    }
    
    @AfterEach void close() throws IOException { if (server != null) server.close(); }
    
    private PlatformConfig config(JsonElement adapter) {
        return RuntimeFixture.platform(root.resolve("absent"), obj("providers", obj("audio", obj("nixos", adapter))));
    }
    
    @Test void test_resource_mounts_reuse_existing_roots_but_keep_external_alias_targets() throws IOException {
        Map<String, String> files = Map.of(
            "/nix/store/selected/share/icons", "/nix/store/selected/share/icons",
            "/nix/store/selected/share/fonts/alias.ttf", "/nix/store/unselected/share/fonts/one.ttf",
            "/home/app/theme", "/nix/store/selected/share/theme",
            "/outside/icons", "/nix/store/selected/share/icons",
            "/nix/store/selected-similar/leaf", "/nix/store/selected-similar/leaf"
        );
        List<String> arguments = Mounts.resources(files, List.of(), List.of("/nix/store/selected", "/home/app"));
        List<List<String>> triples = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i += 3) triples.add(arguments.subList(i, i + 3));
        assertEquals(3, triples.size());
        assertEquals(Set.<List<String>>of(
            List.of("--ro-bind", "/nix/store/unselected/share/fonts/one.ttf", "/nix/store/unselected/share/fonts/one.ttf"),
            List.of("--ro-bind", "/nix/store/selected/share/icons", "/outside/icons"),
            List.of("--ro-bind", "/nix/store/selected-similar/leaf", "/nix/store/selected-similar/leaf")
        ), new HashSet<>(triples));
    }
    
    @Test void test_resource_directory_views_deduplicate_only_identical_children() throws IOException {
        Path tree = Files.createDirectory(root.resolve("tree"));
        Path child = Files.writeString(tree.resolve("child"), "ordinary");
        Path replacement = Files.writeString(root.resolve("replacement"), "override");
        Path view = root.resolve("view");
        assertEquals(
            List.of(
                "--ro-bind", tree.toString(), view.toString(),
                "--ro-bind", replacement.toString(), view.resolve("overridden").toString()
            ),
            Mounts.resources(Map.of(
                view.resolve("child").toString(), child.toString(),
                view.resolve("overridden").toString(), replacement.toString(),
                view.toString(), tree.toString()
            ), List.of(), List.of())
        );
    }
    
    @Test void test_nixos_requires_enabled_adapter_without_direct_fallback() {
        PlatformConfig absent = RuntimeFixture.platform(root.resolve("absent"), obj());
        var env = new HashMap<>(environment);
        env.put("PULSE_SERVER", "unix:" + endpoint);
        String message = assertThrows(IllegalArgumentException.class, () -> Audio.connect(absent, Policy.Audio.NIXOS, env)).getMessage();
        Json.require(message != null, "Expected fixture value");
        assertTrue(message.contains("nixos") && message.contains("adapter"));
    }
    
    @Test void test_nixos_uses_fixed_real_socket_and_ignores_session_redirection() throws IOException {
        var env = new HashMap<>(environment);
        env.putAll(Map.of("PULSE_SERVER", "unix:/unrelated", "PULSE_COOKIE", "/unrelated-cookie"));
        var result = Audio.connect(config, Policy.Audio.NIXOS, env);
        assertEquals(Map.of(endpoint.toString(), endpoint.toString()), result.files());
        assertEquals("unix:" + endpoint, result.environment().get("PULSE_SERVER"));
        assertFalse(result.environment().containsKey("PULSE_COOKIE"));
        assertTrue(result.files().keySet().stream().noneMatch(path -> path.contains("pipewire")));
    }
    
    @Test void test_nixos_adapter_wrong_type_missing_endpoint_and_symlink_fail() throws IOException {
        List<JsonElement> descriptors = new ArrayList<>(JsonParser.parseString(
            "[true,{}, {\"socketPath\":12},{\"socketPath\":\"relative\"}," +
                "{\"socketPath\":null},{\"socketPath\":\"/bad\\u0000\"}]"
        ).getAsJsonArray().asList());
        descriptors.add(obj("socketPath", root.resolve("absent").toString()));
        descriptors.add(obj("socketPath", endpoint.toString(), "extra", "bad"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), endpoint);
        descriptors.add(obj("socketPath", alias.toString()));
        for (JsonElement adapter : descriptors) {
            assertThrows(
                IllegalArgumentException.class,
                () -> Audio.connect(config(adapter), Policy.Audio.NIXOS, environment),
                adapter.toString()
            );
        }
    }
    
    @Test void test_direct_requires_socket_when_audio_requested() throws IOException {
        Assumptions.assumeFalse(Files.exists(Path.of("/run/pulse/native")), "A host-wide Pulse server is present");
        String message = assertThrows(IllegalArgumentException.class, () -> Audio.connect(config, Policy.Audio.DIRECT, environment)).getMessage();
        Json.require(message != null, "Expected fixture value");
        assertTrue(message.contains("audio") && message.contains("socket"));
        var env = new HashMap<>(environment);
        env.put("PULSE_SERVER", "unix:" + endpoint);
        assertEquals("unix:" + endpoint, Audio.connect(config, Policy.Audio.DIRECT, env).environment().get("PULSE_SERVER"));
    }
    
    @Test void test_unknown_provider_does_not_invoke_discovery() {
        assertThrows(IllegalArgumentException.class, () -> Audio.connect(config, null, environment));
        // Other Python dynamic argument types cannot reach the enum-typed API at all.
        for (JsonElement invalid : JsonParser.parseString("[null,true,\"auto\",\"NixOS\"]").getAsJsonArray()) {
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                "name", "app", "command", List.of("app"), "audio", true, "providers", obj("audio", invalid)
            )));
        }
    }
    
}