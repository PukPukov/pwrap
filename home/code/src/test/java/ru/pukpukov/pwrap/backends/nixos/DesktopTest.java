package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import com.google.gson.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import ru.pukpukov.pwrap.core.*;

/** test_desktop.py: same fixtures/assertions, real native file acquisition and UNIX sockets. */
@EnabledInNativeImage
class DesktopTest {
    
    final Path root;
    Path home, configHome, data, fontconf, fontList, fontConfigs, commands;
    Map<String, String> environment;
    JsonObject descriptor;
    
    DesktopTest(@TempDir Path root) throws Exception {
        this.root = root;
        home = Files.createDirectory(root.resolve("different-home"));
        configHome = root.resolve("xdg-config");
        data = root.resolve("shared-data");
        fontconf = write(root, "fontconfig/fonts.conf", "<fontconfig/>");
        fontList = write(root, "font-list", "");
        fontConfigs = write(root, "font-configs", "");
        commands = write(root, "commands", "");
        environment = new HashMap<>(Map.of(
            "HOME", home.toString(), "XDG_CONFIG_HOME", configHome.toString(), "XDG_DATA_HOME", home.resolve("data").toString(),
            "XDG_DATA_DIRS", data.toString(), "XDG_CONFIG_DIRS", root.resolve("system-config").toString(),
            "FONTCONFIG_FILE", fontconf.toString(), "PATH", ""
        ));
        descriptor = RuntimeFixture.descriptor(root.resolve("absent"), obj("desktop", obj()));
        descriptor.addProperty("fcList", script(root, "fc-list", output(fontList)).toString());
        script(root, "fc-conflist", output(fontConfigs));
        descriptor.addProperty("gsettings", script(
            root, "gsettings",
            """
            printf '%%s|%%s\n' "$1" "$2" >> %s
            printf '%%s\n' "$2 font-name 'Font With Spaces 11'" "$2 cursor-size 24" \
              "$2 private-setting 'not exported'" "unrelated.schema font-name 'not exported'"
            """.formatted(quote(commands))
        ).toString());
    }
    
    private static String quote(Path path) { return "'" + path.toString().replace("'", "'\"'\"'") + "'"; }
    
    private static String output(Path file) {
        return "while IFS= read -r line || [ -n \"$line\" ]; do printf '%s\\n' \"$line\"; done < " + quote(file) + "\n";
    }
    
    private static Path script(Path root, String name, String body) throws IOException {
        Path path = write(root, "tools/" + name, "#!" + Commands.executable("sh") + "\n" + body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
    
    private Path write(String path, String text) throws IOException { return write(root, path, text); }
    
    private static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(Fs.parent(file));
        return Files.writeString(file, text);
    }
    
    private Path write(String path) throws IOException { return write(path, "test"); }
    
    private Desktop.Appearance discover() throws Exception {
        return Desktop.discover(new PlatformConfig(descriptor), new Session.Environment(environment), NixStorage.PROVENANCE);
    }
    
    private static ServerSocketChannel socket(Path endpoint) throws IOException {
        Files.createDirectories(Fs.parent(endpoint));
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(endpoint));
            return server;
        } catch (IOException | RuntimeException error) {
            server.close();
            throw error;
        }
    }
    
    private Map<String, String> pulse(Path endpoint) {
        Map<String, String> env = new HashMap<>(environment);
        env.put("PULSE_SERVER", "unix:" + endpoint);
        return env;
    }
    
    private static void error(String message, Executable action) {
        String actual = assertThrows(IllegalArgumentException.class, action).getMessage();
        Json.require(actual != null, "Expected fixture value");
        assertTrue(actual.contains(message));
    }
    
    @Test void test_canonical_subtree_is_not_walked_again_but_code_scan_is_independent() throws Exception {
        Path plugin = write("shared-data/themes/Test/modules/test.so");
        Desktop.Selection resources = new Desktop.Selection(home.toString(), NixStorage.PROVENANCE);
        resources.tree(data.resolve("themes").toString());
        // A new disallowed link would make another scan fail. The cached data scan must skip it.
        Path later = Files.createSymbolicLink(Fs.parent(plugin).resolve("later"), home);
        resources.tree(Fs.parent(plugin).toString());
        var supplement = Desktop.Supplement.read(JsonNull.INSTANCE);
        assertTrue(resources.appearance(supplement, Map.of(), "").files().containsKey(Fs.parent(plugin).toString()));
        Files.delete(later);
        resources.tree(Fs.parent(plugin).toString(), true);
        assertTrue(resources.appearance(supplement, Map.of(), "").runtimeRoots().contains(plugin.toString()));
    }
    
    @Test void test_settings_batch_per_schema_preserves_values_and_filters_keys() throws Exception {
        String text = Desktop.settings(new PlatformConfig(descriptor), new Session.Environment(environment));
        List<String> calls = Files.readAllLines(commands);
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.startsWith("list-recursively|")));
        assertEquals(Set.of("list-recursively|org.gnome.desktop.interface", "list-recursively|org.gnome.desktop.wm.preferences"), new HashSet<>(calls));
        assertTrue(text.contains("font-name='Font With Spaces 11'"));
        assertTrue(text.contains("cursor-size=24"));
        assertFalse(text.contains("not exported"));
    }
    
    @Test void test_fonts_and_settings_without_module_and_with_alternate_xdg_paths() throws Exception {
        Path font = write("font-package/share/fonts/test.ttf");
        Files.writeString(fontList, font + "\n");
        Path settings = write("xdg-config/gtk-3.0/settings.ini", "[Settings]\ngtk-decoration-layout=:minimize,maximize,close\n");
        Path bookmark = write("xdg-config/gtk-3.0/bookmarks", "file:///private");
        var resources = discover();
        for (Path selected : List.of(fontconf, Fs.parent(font), settings)) assertEquals(selected.toString(), resources.files().get(selected.toString()));
        assertFalse(resources.files().containsKey(bookmark.toString()));
        assertEquals(configHome.toString(), resources.environment().get("XDG_CONFIG_HOME"));
        assertTrue(resources.settings().contains("[org/gnome/desktop/wm/preferences]"));
        for (String broad : List.of(home.toString(), data.toString(), "/nix/store")) assertFalse(resources.files().containsKey(broad));
    }
    
    @Test void test_discovery_owns_the_trusted_metadata_supplement() throws Exception {
        Path extra = write("trusted-extra");
        List<String> roots = List.of("/nix/store/trusted-theme/lib/theme.so");
        descriptor.getAsJsonObject("metadata").add("desktop", obj("files", Map.of("/appearance-extra", extra.toString()),
            "environment", Map.of("GTK_THEME", "TrustedTheme", "GIO_EXTRA_MODULES", "/payload-only"),
            "runtimeRoots", roots, "readOnly", List.of(extra.toString())
        ));
        var resources = discover();
        assertEquals(extra.toString(), resources.files().get("/appearance-extra"));
        assertEquals(roots, resources.runtimeRoots());
        assertEquals(List.of(extra.toString()), resources.readOnly());
        assertEquals("TrustedTheme", resources.environment().get("GTK_THEME"));
        assertEquals("/payload-only", resources.environment().get("GIO_EXTRA_MODULES"));
    }
    
    @Test void test_only_appearance_subdirectories_of_data_paths() throws Exception {
        Path icons = write("shared-data/icons/Test/index.theme"), themes = write("shared-data/themes/Test/gtk-3.0/gtk.css");
        write("shared-data/applications/private.desktop");
        var resources = discover();
        assertTrue(resources.files().containsKey(Fs.parent(Fs.parent(icons)).toString()));
        assertTrue(resources.files().containsKey(Fs.parent(Fs.parent(Fs.parent(themes))).toString()));
        assertFalse(resources.files().containsKey(data.toString()));
        assertFalse(resources.files().containsKey(data.resolve("applications").toString()));
    }
    
    @Test void test_appearance_symlink_cannot_grant_private_directory() throws Exception {
        Path secret = write("private/secret"), icons = Files.createDirectories(data.resolve("icons"));
        Files.createSymbolicLink(icons.resolve("malicious"), Fs.parent(secret));
        error("appearance symlink", this::discover);
    }
    
    @Test void downward_relative_links_reuse_the_selected_tree_but_do_not_hide_an_escape() throws Exception {
        Path icon = write("shared-data/icons/Test/original/icon.png"), theme = Fs.parent(Fs.parent(icon));
        Files.createSymbolicLink(theme.resolve("alias.png"), Path.of("original/icon.png"));
        Files.createSymbolicLink(theme.resolve("chain.png"), Path.of("alias.png"));
        var appearance = discover();
        assertTrue(appearance.files().containsKey(data.resolve("icons").toString()));
        assertEquals("test", Files.readString(theme.resolve("chain.png")));
        Path secret = write("private/secret"), bridge = Files.createSymbolicLink(theme.resolve("bridge"), Fs.parent(secret));
        Files.createSymbolicLink(theme.resolve("nested.png"), Path.of("bridge/secret"));
        assertTrue(Files.isSymbolicLink(bridge));
        error("appearance symlink", this::discover);
    }
    
    @Test void test_qt_only_appearance_plugins_and_no_loader_environment() throws Exception {
        Path plugins = root.resolve("qt-plugins"), selected = write("qt-plugins/styles/test.so");
        write("qt-plugins/other/unrelated.so");
        environment.putAll(Map.of(
            "QT_PLUGIN_PATH", plugins.toString(), "QT_QPA_PLATFORMTHEME", "qt6ct",
            "LD_PRELOAD", "/secret", "GIO_EXTRA_MODULES", "/unrelated"
        ));
        var resources = discover();
        assertTrue(resources.runtimeRoots().contains(selected.toString()));
        assertTrue(resources.files().containsKey(plugins.resolve("styles").toString()));
        assertFalse(resources.files().containsKey(plugins.resolve("other").toString()));
        assertEquals("qt6ct", resources.environment().get("QT_QPA_PLATFORMTHEME"));
        assertFalse(resources.environment().containsKey("LD_PRELOAD"));
        assertFalse(resources.environment().containsKey("GIO_EXTRA_MODULES"));
    }
    
    @Test void test_audio_follows_configured_local_pulse_not_raw_pipewire() throws Exception {
        Path endpoint = root.resolve("custom-sound/socket");
        try (var _ = socket(endpoint)) {
            environment.put("PULSE_SERVER", "unix:" + endpoint);
            Path cookie = write("xdg-config/pulse/cookie");
            var resources = Audio.discover(environment);
            assertEquals(Map.of(endpoint.toString(), endpoint.toString(), cookie.toString(), cookie.toString()), resources.files());
            assertEquals("unix:" + endpoint, resources.environment().get("PULSE_SERVER"));
            assertTrue(resources.files().keySet().stream().noneMatch(path -> path.contains("pipewire")));
        }
    }
    
    @Test void test_no_audio_server_is_a_valid_denied_capability() throws Exception {
        // Unlike the Python mocked exists(), native discovery observes the real global fallback.
        Assumptions.assumeFalse(Files.exists(Path.of("/run/pulse/native")), "A host-wide Pulse server is present");
        environment.put("XDG_RUNTIME_DIR", root.resolve("no-runtime").toString());
        var resources = Audio.discover(environment);
        assertEquals(Map.of(), resources.files());
        assertEquals(Map.of(), resources.environment());
        environment.put("PULSE_SERVER", "unix:" + root.resolve("missing"));
        error("does not exist", () -> Audio.discover(environment));
    }
    
    @Test void test_audio_rejects_file_instead_of_socket() throws Exception {
        Path endpoint = write("not-a-socket");
        error("local UNIX socket", () -> Audio.discover(pulse(endpoint)));
    }
    
    @Test void test_audio_cookie_symlink_cannot_grant_an_unrelated_file() throws Exception {
        Path endpoint = root.resolve("pulse/native");
        try (var _ = socket(endpoint)) {
            Path secret = write("private/secret", "not a pulse cookie"), cookie = configHome.resolve("pulse/cookie");
            Files.createDirectories(Fs.parent(cookie));
            Files.createSymbolicLink(cookie, secret);
            var env = pulse(endpoint);
            env.put("PULSE_COOKIE", cookie.toString());
            error("regular file", () -> Audio.discover(env));
        }
    }
    
    @Test void test_audio_cookie_parent_symlink_cannot_grant_an_unrelated_file() throws Exception {
        Path endpoint = root.resolve("pulse/native");
        try (var _ = socket(endpoint)) {
            Path secret = write("private/pulse/cookie", "not a pulse cookie");
            Path alias = Files.createSymbolicLink(root.resolve("xdg"), Fs.parent(Fs.parent(secret)));
            var env = pulse(endpoint);
            env.put("XDG_CONFIG_HOME", alias.toString());
            error("canonical", () -> Audio.discover(env));
        }
    }
    
    @Test void test_fontconfig_includes_do_not_overlay_symlink_inside_bound_directory() throws Exception {
        Path config = write("fontconfig/conf.d/rule.conf", "<fontconfig/>");
        Files.writeString(fontConfigs, "+ " + config + ": fontconfig rule\n");
        var resources = discover();
        assertTrue(resources.files().containsKey(Fs.parent(config).toString()));
        assertFalse(resources.files().containsKey(config.toString()));
    }
    
    @Test void test_fontconfig_includes_are_discovered_as_exact_files() throws Exception {
        Path custom = write("unusual-config/aliases.conf", "<fontconfig/>");
        Path secret = write("unusual-config/secret");
        Files.writeString(fontConfigs, "+ " + custom + ": fontconfig rule\n");
        var resources = discover();
        assertTrue(resources.files().containsKey(custom.toString()));
        assertFalse(resources.files().containsKey(Fs.parent(custom).toString()));
        assertFalse(resources.files().containsKey(secret.toString()));
    }
    
    @Test void test_broad_cursor_search_path_is_not_a_grant() {
        environment.put("XCURSOR_PATH", home.toString());
        error("broad appearance", this::discover);
    }
    
    @Test void test_fontconfig_search_does_not_mount_its_parent_directory() throws Exception {
        Path file = write("different-home/fonts.conf", "<fontconfig/>"), secret = write("different-home/private-note");
        environment.putAll(Map.of("FONTCONFIG_FILE", file.toString(), "FONTCONFIG_PATH", home.toString()));
        var resources = discover();
        assertFalse(resources.files().containsKey(home.toString()));
        assertFalse(resources.files().containsKey(secret.toString()));
    }
    
    @Test void test_packaged_icon_leaf_outside_share_is_narrow_not_a_closure() {
        String output = "/nix/store/" + "a".repeat(32) + "-app";
        // Same regular-file/directory classification as the original mocked isfile(), without writing the Nix store.
        Desktop.Selection.checkLink("/icons/app.png", output + "/lib/app/icon.png", false, true, NixStorage.PROVENANCE);
        error("appearance symlink", () -> Desktop.Selection.checkLink("/icons/app", output, false, false, NixStorage.PROVENANCE));
    }
    
    @Test void test_library_symlink_cannot_turn_config_data_into_a_closure() throws Exception {
        Path module = Files.createDirectories(root.resolve("gtk/modules")), config = write("private.conf");
        Files.createSymbolicLink(module.resolve("bad.so"), config);
        environment.put("GTK_PATH", Fs.parent(module).toString());
        error("not a library", this::discover);
    }
    
    @Test void test_module_does_not_choose_theme_or_modify_audio_implicitly() throws IOException {
        String source = Files.readString(Path.of("home/code/src/main/backends/nixos/desktop.nix"));
        assertFalse(source.contains("pkgs.kdePackages"));
        assertFalse(source.contains("plasma6.enable"));
        assertTrue(source.contains("audio.enable"));
        assertFalse(source.contains("rootPaths = config.fonts.packages"));
    }
    
}