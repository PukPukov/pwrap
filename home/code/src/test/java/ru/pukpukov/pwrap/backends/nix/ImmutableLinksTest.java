package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import ru.pukpukov.pwrap.core.Fs;
import ru.pukpukov.pwrap.core.Resources;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import static org.junit.jupiter.api.Assertions.*;

/** ImmutableLinkTests + ImmutableResourceTests from test_storage.py. */
@EnabledInNativeImage
class ImmutableLinksTest {
    
    final Path root;
    Path store, selected;
    List<Grant> grants;
    
    ImmutableLinksTest(@TempDir Path root) throws IOException {
        this.root = root;
        store = Files.createDirectory(root.resolve("store"));
        selected = Files.createDirectories(store.resolve("generation/selected"));
        grants = List.of(grant(selected, false));
    }
    
    private static Grant grant(Path path, boolean writable) { return new Grant(path.toString(), path.toString(), writable); }
    
    private static Resources.Binding binding(Path source, Path destination) {
        return new Resources.Binding(source.toString(), destination.toString());
    }
    
    private Path file(String name) throws IOException {
        Path path = store.resolve(name);
        Files.createDirectories(Fs.parent(path));
        return Files.writeString(path, "test");
    }
    
    private Set<Resources.Binding> dependencies() throws Exception { return dependencies(grants, List.of()); }
    
    private Set<Resources.Binding> dependencies(List<Grant> selectedGrants, List<String> immutable) throws Exception {
        List<Resources.Resource> resources = NixResources.canonical(selectedGrants, store.toString());
        resources.addAll(NixResources.immutable(immutable, selectedGrants, store.toString()));
        Resources.Dependencies result = NixResources.dependencies(resources, store.toString(), null, null);
        Set<Resources.Binding> all = new HashSet<>(result.files());
        all.addAll(result.links());
        return all;
    }
    
    @Test void test_explicit_immutable_subtree_resolves_only_selected_link_targets() throws Exception {
        Path target = file("source/settings.json");
        file("source/private-sibling");
        file("generation/other-app/secret");
        file("generation/selected-similar/secret");
        Files.createSymbolicLink(selected.resolve("settings"), target);
        assertEquals(Set.of(binding(target, target)), dependencies());
    }
    
    @Test void test_mutable_or_writable_grants_never_add_dependencies() throws Exception {
        Path target = file("private/secret");
        Files.createSymbolicLink(selected.resolve("link"), target);
        Path mutable = Files.createDirectory(root.resolve("mutable-profile"));
        Files.createSymbolicLink(mutable.resolve("link"), target);
        for (Grant value : List.of(grant(selected, true), grant(mutable, false)))
            assertEquals(Set.of(), dependencies(List.of(value), List.of()));
    }
    
    @Test void test_ordinary_alias_cannot_seed_dependency_authority_before_pins() throws Exception {
        Path target = file("private/secret");
        Path alias = Files.createSymbolicLink(root.resolve("ordinary"), target);
        Path parent = Files.createSymbolicLink(root.resolve("ordinary-parent"), Fs.parent(target));
        for (Path source : List.of(alias, parent.resolve(Fs.name(target)))) {
            var resources = NixResources.canonical(List.of(grant(source, false)), store.toString());
            assertEquals(List.of(), resources);
            assertEquals(new Resources.Dependencies(Set.of(), Set.of()), NixResources.dependencies(resources, store.toString(), null, null));
        }
    }
    
    @Test void test_explicit_resource_freezes_controller_resolution_before_walking() throws Exception {
        Path target = file("source/selected");
        Path other = file("private/unselected");
        Path alias = Files.createSymbolicLink(root.resolve("controller"), target);
        var resources = NixResources.immutable(List.of(alias.toString()), List.of(), store.toString());
        Files.delete(alias);
        Files.createSymbolicLink(alias, other);
        var result = NixResources.dependencies(resources, store.toString(), null, null);
        assertEquals(Set.of(binding(target, target)), result.files());
        assertEquals(Set.of(), result.links());
    }
    
    @Test void test_transitive_selected_directories_and_cycles_are_bounded() throws Exception {
        Path target = Fs.parent(file("source/subtree/leaf"));
        Path other = Fs.parent(file("other/subtree/leaf"));
        file("source/private-sibling");
        Files.createSymbolicLink(selected.resolve("first"), target);
        Files.createSymbolicLink(target.resolve("next"), other);
        Files.createSymbolicLink(other.resolve("back"), target);
        assertEquals(Set.of(binding(target, target), binding(other, other)), dependencies());
    }
    
    @Test void test_out_of_store_links_are_never_walked_or_added() throws Exception {
        Path privateDir = Files.createDirectory(root.resolve("private"));
        Path leaked = file("unrelated/should-not-be-discovered");
        Files.createSymbolicLink(privateDir.resolve("link-back-to-store"), leaked);
        Files.createSymbolicLink(selected.resolve("out"), privateDir);
        assertEquals(Set.of(), dependencies());
    }
    
    @Test void test_ordinary_leaf_or_already_granted_target_needs_no_duplicate_mount() throws Exception {
        Path leaf = file("source/leaf");
        Files.createSymbolicLink(selected.resolve("leaf"), leaf);
        assertEquals(Set.of(), dependencies(List.of(grant(selected, false), grant(leaf, false)), List.of()));
        assertEquals(Set.of(), dependencies(List.of(grant(leaf, false)), List.of()));
    }
    
    @Test void test_dangling_links_and_internal_relative_links_add_no_authority() throws Exception {
        Files.writeString(selected.resolve("file"), "selected");
        Files.createSymbolicLink(selected.resolve("relative"), Path.of("file"));
        Files.createSymbolicLink(selected.resolve("dangling"), store.resolve("absent/file"));
        assertEquals(Set.of(), dependencies());
    }
    
    @Test void test_new_ancestor_directory_target_is_rejected_not_promoted() throws Exception {
        file("generation/private-sibling");
        Files.createSymbolicLink(selected.resolve("parent"), Path.of(".."));
        assertThrows(IllegalArgumentException.class, this::dependencies);
        assertEquals(Set.of(), dependencies(List.of(grant(Fs.parent(selected), false)), List.of()));
    }
    
    @Test void test_explicit_directory_alias_does_not_imply_canonical_path_mount() throws Exception {
        Path target = Fs.parent(file("source/subtree/leaf"));
        Files.createSymbolicLink(target.resolve("back"), target);
        Path alias = Files.createSymbolicLink(store.resolve("generation/alias"), target);
        assertEquals(Set.of(binding(target, target)), dependencies(List.of(), List.of(alias.toString())));
    }
    
    private record Aliases(Path alias, Path target, Path intermediate, Path managed) {}
    
    private Aliases aliasChain(boolean directory) throws IOException {
        Path leaf = file("source/data/leaf");
        Path target = directory ? Fs.parent(leaf) : leaf;
        Path generation = store.resolve("generation");
        Path files = Files.createDirectory(store.resolve("file-output"));
        Files.createSymbolicLink(files.resolve("entry"), target);
        Files.createSymbolicLink(generation.resolve("files"), files);
        Path controller = Files.createSymbolicLink(root.resolve("controller"), generation);
        return new Aliases(controller.resolve("files/entry"), target, generation.resolve("files/entry"), files.resolve("entry"));
    }
    
    private static Set<Resources.Binding> exactAliases(Aliases a) {
        return Set.of(binding(a.target, a.target), binding(a.target, a.intermediate), binding(a.target, a.managed));
    }
    
    @Test void test_explicit_resource_alias_adds_exact_intermediate_store_leaf_suffixes() throws Exception {
        Aliases a = aliasChain(false);
        file("generation/private");
        file("file-output/private");
        assertEquals(exactAliases(a), dependencies(List.of(), List.of(a.alias.toString())));
    }
    
    @Test void test_explicit_directory_alias_walks_only_selected_canonical_subtree() throws Exception {
        Aliases a = aliasChain(true);
        Path extra = file("external/selected/leaf");
        Files.createSymbolicLink(a.target.resolve("extra"), extra);
        file("source/private");
        Set<Resources.Binding> expected = new HashSet<>(exactAliases(a));
        expected.add(binding(extra, extra));
        assertEquals(expected, dependencies(List.of(), List.of(a.alias.toString())));
        assertEquals(Set.of(), dependencies(List.of(grant(a.alias, true)), List.of()));
    }
    
    @Test void test_explicit_outstore_final_alias_never_walks_private_subtree() throws Exception {
        Path target = file("secret/data");
        Path privateDir = Files.createDirectory(root.resolve("private"));
        Files.createSymbolicLink(privateDir.resolve("back-to-store"), target);
        Path alias = Files.createSymbolicLink(root.resolve("alias"), privateDir);
        assertEquals(Set.of(), dependencies(List.of(grant(alias, false)), List.of()));
    }
    
    @Test void test_admin_exact_alias_may_cross_outstore_controller_but_not_its_siblings() throws Exception {
        Path target = file("data/selected");
        Path controller = Files.createDirectory(root.resolve("controller"));
        Files.createSymbolicLink(controller.resolve("selected"), target);
        file("data/private");
        Path alias = Files.createSymbolicLink(root.resolve("alias"), controller).resolve("selected");
        assertEquals(Set.of(binding(target, target)), dependencies(List.of(), List.of(alias.toString())));
    }
    
    @Test void test_immutable_alias_link_expands_intermediate_store_paths_too() throws Exception {
        Aliases a = aliasChain(false);
        Files.createSymbolicLink(selected.resolve("link"), a.intermediate);
        assertEquals(exactAliases(a), dependencies());
    }
    
    @Test void test_immutable_child_cannot_hop_outside_store_then_back_inside() throws Exception {
        Path secret = file("private/data");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(outside.resolve("back"), secret);
        Path relay = Files.createSymbolicLink(store.resolve("relay"), outside);
        Files.createSymbolicLink(selected.resolve("relay"), relay.resolve("back"));
        assertEquals(Set.of(), dependencies());
    }
    
    @Test void test_directory_intermediate_aliases_are_links_to_exact_canonical_mount() throws Exception {
        Aliases a = aliasChain(true);
        var result = NixResources.dependencies(
            NixResources.immutable(List.of(a.alias.toString()), List.of(), store.toString()),
            store.toString(), null, null
        );
        assertEquals(Set.of(binding(a.target, a.target)), result.files());
        assertEquals(Set.of(binding(a.target, a.intermediate), binding(a.target, a.managed)), result.links());
    }
    
    @Test void test_leaf_intermediate_aliases_remain_exact_file_mounts() throws Exception {
        Aliases a = aliasChain(false);
        var result = NixResources.dependencies(
            NixResources.immutable(List.of(a.alias.toString()), List.of(), store.toString()),
            store.toString(), null, null
        );
        assertEquals(exactAliases(a), result.files());
        assertEquals(Set.of(), result.links());
    }
    
    @Test void test_controller_alias_is_explicit_resource_not_a_followed_path_grant() throws Exception {
        Path leaf = file("output/selected");
        Path alias = Files.createSymbolicLink(root.resolve("controller"), leaf);
        var resource = NixResources.immutable(List.of(alias.toString()), List.of(), store.toString()).getFirst();
        assertEquals(leaf.toString(), resource.source());
        assertEquals(alias.toString(), resource.destination());
        
        Path privateDir = Files.createDirectory(root.resolve("private"));
        Files.delete(alias);
        Files.createSymbolicLink(alias, privateDir);
        assertThrows(IllegalArgumentException.class, () -> NixResources.immutable(List.of(alias.toString()), List.of(), store.toString()));
        
        Files.delete(alias);
        Files.createSymbolicLink(alias, leaf);
        assertThrows(IllegalArgumentException.class, () -> NixResources.immutable(
            List.of(alias.toString()), List.of(grant(root, true)), store.toString()
        ));
        
        Files.delete(alias);
        Files.createSymbolicLink(alias, Fs.parent(leaf));
        assertThrows(IllegalArgumentException.class, () -> NixResources.immutable(List.of(alias.toString()), List.of(), store.toString()));
    }
    
}