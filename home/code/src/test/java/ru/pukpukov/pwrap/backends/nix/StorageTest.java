package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** StorageTests from test_storage.py; actual files and an explicit Nix query boundary. */
class StorageTest {
    
    final Path root;
    
    StorageTest(@TempDir Path root) { this.root = root; }
    
    private static final String APP = "/nix/store/" + "a".repeat(32) + "-app", DYNAMIC = "/nix/store/" + "b".repeat(32) + "-dynamic";
    
    private NixStorage.Tools config(String base) throws IOException {
        Path closure = Files.writeString(root.resolve("base"), base);
        return new NixStorage.Tools("/tools/nixStore", closure.toString(), "/tool", "/tools/portalTools/bin/xdg-open");
    }
    
    private static Policy policy(JsonObject extra) {
        JsonObject value = obj("command", List.of(APP + "/bin/app"));
        extra.entrySet().forEach(e -> value.add(e.getKey(), e.getValue()));
        return Policy.read(value);
    }
    
    private static String noQuery(List<String> command) { throw new AssertionError("Unexpected Nix closure query: " + command); }
    
    @Test void test_precomputed_closure_avoids_query_but_dynamic_missing_roots_are_resolved() throws Exception {
        NixStorage.Tools config = config(APP + "\n");
        NixStorage.resolve(policy(obj()), List.of(), Storage.Inputs.empty(), config, StorageTest::noQuery);
        List<List<String>> queries = new ArrayList<>();
        NixStorage.resolve(
            policy(obj("runtime", List.of(DYNAMIC + "/bin/helper"))), List.of(), Storage.Inputs.empty(), config,
            command -> {
                queries.add(command);
                return "";
            }
        );
        assertEquals(1, queries.size());
        assertEquals(List.of(DYNAMIC), queries.getFirst().subList(3, queries.getFirst().size()));
        Path manifest = Files.writeString(root.resolve("manifest-closure"), DYNAMIC + "\n");
        NixStorage.resolve(
            policy(obj("closure", manifest.toString())), List.of(),
            new Storage.Inputs(List.of(DYNAMIC + "/bin/helper"), List.of()), config, StorageTest::noQuery
        );
    }
    
    @Test void test_web_handlers_cover_package_path_precedence_only_when_granted() throws Exception {
        String utils = "/nix/store/" + "c".repeat(32) + "-xdg-utils", tools = "/nix/store/" + "d".repeat(32) + "-web-tools";
        Set<String> selected = Set.of(APP, utils, tools), leaves = Set.of(utils + "/bin/xdg-open", tools + "/bin/xdg-open");
        var enabled = NixStorage.openerTargets(true, selected, tools + "/bin/xdg-open", path -> leaves.contains(path) ? path : null);
        var disabled = NixStorage.openerTargets(
            false, selected, tools + "/bin/xdg-open", path -> { throw new AssertionError("Ungranted handler discovery"); }
        );
        assertEquals(List.of(utils + "/bin/xdg-open"), enabled);
        assertEquals(List.of(), disabled);
        assertEquals(Set.of(APP, utils, tools), selected);
    }
    
    @Test void test_web_handler_alias_outside_selected_closure_is_rejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> NixStorage.openerTargets(true, Set.of(APP), DYNAMIC + "/bin/xdg-open", path -> "/outside-handler")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> NixStorage.openerTargets(true, Set.of(APP), APP + "/own-helper", path -> DYNAMIC + "/bin/xdg-open")
        );
    }
    
    @Test void test_nix_roots() throws Exception {
        assertEquals("/nix/store/abc-package", NixStorage.root("/nix/store/abc-package/bin/a"));
        assertNull(NixStorage.root("/usr/bin/a"));
    }
    
    @Test void test_no_native_fallback() throws Exception {
        NixStorage.Tools config = config("");
        assertThrows(IllegalArgumentException.class, () -> NixStorage.resolve(
            policy(obj("command", List.of("/usr/bin/true"))), List.of(), Storage.Inputs.empty(), config, StorageTest::noQuery
        ));
    }
    
    @Test void test_closure_validation() throws Exception {
        NixStorage.Tools config = config("/\n");
        assertThrows(IllegalArgumentException.class, () -> NixStorage.resolve(
            policy(obj()), List.of(), Storage.Inputs.empty(), config, command -> ""
        ));
    }
    
    @Test void test_no_native_runtime_fallback() throws Exception {
        NixStorage.Tools config = config("");
        assertThrows(IllegalArgumentException.class, () -> NixStorage.resolve(
            policy(obj("runtime", List.of("/usr/bin/python"))), List.of(), Storage.Inputs.empty(), config, StorageTest::noQuery
        ));
    }
    
    @Test void test_desktop_code_closure_and_narrow_data_files_are_independent() throws Exception {
        NixStorage.Tools config = config("");
        String plugin = DYNAMIC + "/lib/theme.so";
        var data = new Resources.Binding("/nix/store/" + "c".repeat(32) + "-generation/selected", "/etc/selected");
        List<List<String>> queries = new ArrayList<>();
        Storage.Runtime resolved = NixStorage.resolve(
            policy(obj()), List.of(), new Storage.Inputs(List.of(plugin), List.of(data)), config,
            command -> {
                queries.add(command);
                return "";
            }
        );
        assertEquals(Set.of(APP, DYNAMIC), new HashSet<>(queries.getFirst().subList(3, queries.getFirst().size())));
        assertEquals(List.of(data), resolved.files());
        assertEquals(List.of(), resolved.paths());
    }
    
    @Test void test_stale_home_metadata_is_not_an_authority_source() throws Exception {
        NixStorage.Tools config = config("");
        Files.writeString(root.resolve("home-files.json"), Json.encode(List.of(obj(
            "target", "/some-home/app/settings", "managed", DYNAMIC, "source", DYNAMIC
        ))).toString());
        Storage.Runtime resolved = NixStorage.resolve(
            policy(obj()), List.of(new Grant("/some-home/app", "/some-home/app", true)), Storage.Inputs.empty(), config, command -> ""
        );
        assertEquals(List.of(), resolved.files());
    }
    
    @Test void test_nix_backend_does_not_silently_ignore_native_appearance_code() throws Exception {
        NixStorage.Tools config = config("");
        assertThrows(IllegalArgumentException.class, () -> NixStorage.resolve(
            policy(obj()), List.of(), new Storage.Inputs(List.of("/usr/lib/theme.so"), List.of()), config, StorageTest::noQuery
        ));
    }
    
    @Test void test_exact_leaves_are_bound_directly_without_parent_promotion_or_copy() throws Exception {
        List<Path> paths = List.of(root.resolve("one"), root.resolve("two"));
        Map<String, String> files = new LinkedHashMap<>();
        for (Path path : paths) {
            Files.writeString(path, Fs.name(path));
            files.put(path.toString(), path.toString());
        }
        var bindings = Resources.bindings(files, List.of());
        assertEquals(paths.stream().map(p -> new Resources.Binding(p.toString(), p.toString())).toList(), bindings);
        assertFalse(bindings.contains(new Resources.Binding(root.toString(), root.toString())));
    }
    
}