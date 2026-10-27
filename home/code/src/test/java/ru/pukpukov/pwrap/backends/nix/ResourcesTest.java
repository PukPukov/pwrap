package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.Mounts;
import ru.pukpukov.pwrap.core.Fs;
import ru.pukpukov.pwrap.core.Json;
import ru.pukpukov.pwrap.core.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.Posix;
import static org.junit.jupiter.api.Assertions.*;

/** Direct port of test_resources.py, using real native descriptors, not mocked syscalls. */
@EnabledInNativeImage
class ResourcesTest {
    
    final Path root;
    Path store;
    
    ResourcesTest(@TempDir Path root) throws IOException {
        this.root = root;
        store = Files.createDirectory(root.resolve("store"));
    }
    
    private Path write(String name) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(Fs.parent(path));
        return Files.writeString(path, "selected");
    }
    
    private Resources.@Nullable Resource resolve(Path path) throws Exception {
        return NixResources.resolve(path.toString(), store.toString(), true);
    }
    
    private static long descriptors() throws IOException {
        try (var fds = Files.list(Path.of("/proc/self/fd"))) { return fds.count(); }
    }
    
    @Test void unicode_paths_roundtrip_through_nio_native_pins_and_symlinks() throws Exception {
        Path selected = write("store/выход/日本語-😀/файл");
        Path link = Files.createSymbolicLink(root.resolve("ссылка-😀"), selected);
        assertEquals("selected", Files.readString(selected));
        assertEquals(selected, Files.readSymbolicLink(link));
        assertEquals(selected.toString(), Json.required(resolve(link), "Expected Unicode fixture").source());
        assertEquals(List.of(selected), Resources.walk(Fs.parent(selected)));
        try (var pin = Posix.open(selected.toString(), Posix.O_RDONLY())) {
            assertEquals(selected, Files.readSymbolicLink(Path.of(pin.path())));
        }
    }
    
    @Test void test_link_target_uses_kernel_resolution_and_closes_its_descriptor() throws Exception {
        Path target = write("actual/item");
        Path directory = Files.createSymbolicLink(root.resolve("directory-alias"), Fs.parent(target));
        Path link = Files.createSymbolicLink(root.resolve("link"), Path.of("directory-alias/item"));
        assertTrue(Files.isDirectory(directory));
        long before = descriptors();
        assertEquals(target.toRealPath().toString(), Json.required(resolve(link), "Expected fixture value").source());
        assertEquals(before, descriptors());
        
        assertNull(resolve(root.resolve("missing")));
        Path loop = Files.createSymbolicLink(root.resolve("loop"), Path.of("loop"));
        assertNull(resolve(loop));
        assertEquals(before, descriptors());
    }
    
    @Test void test_link_target_replacement_is_not_returned_as_the_opened_file() throws Exception {
        Path target = write("target");
        Resources.Access replacement = new Resources.Access() {
            public Posix.Fd open(String path) throws IOException { return Resources.KERNEL.open(path); }
            
            public String physical(Posix.Fd fd) throws IOException {
                String value = Resources.KERNEL.physical(fd);
                Files.move(target, root.resolve("original-inode"));
                Files.writeString(target, "replacement");
                return value;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> NixResources.resolve(target.toString(), store.toString(), true, replacement));
    }
    
    @Test void test_link_target_propagates_unexpected_open_errors() {
        Resources.Access denied = new Resources.Access() {
            public Posix.Fd open(String path) throws IOException { throw new AccessDeniedException(path); }
            
            public String physical(Posix.Fd fd) { throw new AssertionError("Open failure reached readlink"); }
        };
        assertThrows(AccessDeniedException.class, () -> NixResources.resolve("/not-readable", store.toString(), true, denied));
    }
    
    @Test void test_exact_suffixes_are_shared_but_controller_authority_is_explicit() throws Exception {
        Path selected = write("store/package/data/selected");
        Path relay = store.resolve("generation/files");
        Files.createDirectories(Fs.parent(relay));
        Files.createSymbolicLink(relay, Fs.parent(selected));
        Path controller = Files.createSymbolicLink(root.resolve("controller"), Fs.parent(relay));
        Path path = controller.resolve("files/selected");
        Resources.Resource resource = Json.required(resolve(path), "Expected fixture value");
        assertEquals(selected.toString(), resource.source());
        assertEquals(path.toString(), resource.destination());
        assertEquals(Set.of(selected.toString(), relay.resolve("selected").toString()), resource.aliases());
        
        assertNull(NixResources.resolve(path.toString(), store.toString(), false));
        Path bridge = Files.createSymbolicLink(store.resolve("bridge"), controller);
        assertNull(NixResources.resolve(bridge.resolve("files/selected").toString(), store.toString(), false));
    }
    
    @Test void test_mounts_do_not_overlay_aliases_already_supplied_by_a_directory() throws Exception {
        Path target = write("store/package/data/selected");
        Path appearance = Files.createDirectory(root.resolve("appearance"));
        Path alias = Files.createSymbolicLink(appearance.resolve("alias"), Fs.parent(target));
        var arguments = Mounts.resources(
            Map.of(appearance.toString(), appearance.toString(), Fs.parent(target).toString(), Fs.parent(target).toString()),
            Set.of(
                new Resources.Binding(Fs.parent(target).toString(), alias.toString()),
                new Resources.Binding(Fs.parent(target).toString(), root.resolve("outside-alias").toString())
            ),
            List.of()
        );
        assertFalse(arguments.contains(alias.toString()));
        assertEquals(
            List.of("--symlink", Fs.parent(target).toString(), root.resolve("outside-alias").toString()),
            arguments.subList(arguments.size() - 3, arguments.size())
        );
    }
    
    @Test void test_directory_dependencies_and_code_visits_share_a_bounded_walk() throws Exception {
        Path selected = Fs.parent(write("store/package/data/selected"));
        Path dependency = Fs.parent(write("store/library/data/module.so"));
        Files.createSymbolicLink(selected.resolve("module"), dependency);
        Files.createSymbolicLink(dependency.resolve("back"), selected);
        List<Path> visited = new ArrayList<>();
        Resources.Dependencies result = NixResources.dependencies(
            List.of(Json.required(resolve(selected), "Expected fixture value")), store.toString(), null, visited::add
        );
        assertEquals(Set.of(new Resources.Binding(dependency.toString(), dependency.toString())), result.files());
        assertEquals(Set.of(), result.links());
        assertEquals(
            Set.of(selected.resolve("selected"), selected.resolve("module"), dependency.resolve("module.so"), dependency.resolve("back")),
            new HashSet<>(visited)
        );
        assertEquals(4, visited.size());
    }
    
    @Test void transitive_walks_are_reused_within_one_discovery_but_not_the_next() throws Exception {
        Path selected = Fs.parent(write("store/package/data/selected"));
        Path dependency = Fs.parent(write("store/library/data/module.so"));
        Files.createSymbolicLink(selected.resolve("module"), dependency);
        Set<String> walked = new HashSet<>();
        List<Path> inspected = new ArrayList<>();
        var first = NixResources.dependencies(
            List.of(Json.required(resolve(selected), "Expected fixture")), store.toString(), null, inspected::add, walked
        );
        var second = NixResources.dependencies(
            List.of(Json.required(resolve(dependency), "Expected fixture")), store.toString(), null, inspected::add, walked
        );
        assertEquals(Set.of(new Resources.Binding(dependency.toString(), dependency.toString())), first.files());
        assertTrue(second.files().isEmpty());
        assertEquals(1, Collections.frequency(inspected, dependency.resolve("module.so")));
        assertEquals(Set.of(selected.toString(), dependency.toString()), walked);
        
        // A later broader root must prune the already-inspected child, not walk
        // through it again merely because the parent itself is new.
        NixResources.dependencies(
            List.of(Json.required(resolve(Fs.parent(dependency)), "Expected fixture")), store.toString(), null, inspected::add, walked
        );
        assertEquals(1, Collections.frequency(inspected, dependency.resolve("module.so")));
        NixResources.dependencies(
            List.of(Json.required(resolve(dependency), "Expected fixture")), store.toString(), null, inspected::add
        );
        assertEquals(2, Collections.frequency(inspected, dependency.resolve("module.so")), "New discovery must inspect mutable state again");
    }
    
    @Test void walks_classify_links_without_following_them() throws Exception {
        Path file = write("store/package/data/nested/file");
        Path tree = Fs.parent(Fs.parent(file));
        Path outside = write("unselected/private");
        Path link = Files.createSymbolicLink(tree.resolve("link"), Fs.parent(outside));
        Path dangling = Files.createSymbolicLink(tree.resolve("dangling"), Path.of("missing"));
        assertEquals(List.of(), Resources.walk(file));
        assertEquals(List.of(), Resources.walk(link));
        Set<Path> expected = Set.of(file, Fs.parent(file), link, dangling);
        assertEquals(expected, new HashSet<>(Resources.walk(tree)));
        Set<Path> links = new HashSet<>(), inspected = new HashSet<>();
        var resource = Json.required(resolve(tree), "Expected fixture");
        NixResources.dependencies(
            List.of(resource), store.toString(), (path, source) -> {
                links.add(Path.of(path));
                return null;
            }, inspected::add
        );
        assertEquals(expected, inspected);
        assertEquals(Set.of(link, dangling), links);
    }
    
}