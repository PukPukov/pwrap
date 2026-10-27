package ru.pukpukov.pwrap.core;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FsTest {
    
    final Path root;
    FsTest(@TempDir Path root) { this.root = root; }
    
    @Test void ancestor_lookups_preserve_lexical_boundaries() {
        var paths = List.of("", "/", "//", "/a", "/ab", "/a/", "/a/b", "/a//b", "/a/../b", "a", "a/b", "a//b", "/загрузки/😀");
        for (String path : paths) for (String ancestor : paths) {
            boolean expected = path.equals(ancestor) || path.startsWith(ancestor.equals("/") ? "/" : ancestor + "/");
            assertEquals(expected, Fs.under(path, ancestor), path + " under " + ancestor);
            assertEquals(expected, Fs.under(path, Set.of(ancestor)), path + " under singleton " + ancestor);
        }
        for (String path : paths) for (String a : paths) for (String b : paths) {
            Set<String> ancestors = new HashSet<>(List.of(a, b));
            assertEquals(ancestors.stream().anyMatch(ancestor -> Fs.under(path, ancestor)), Fs.under(path, ancestors), path + " under " + ancestors);
        }
        assertFalse(Fs.under("/a", Set.of()));
    }
    
    @Test void existing_paths_use_physical_parents_before_dotdot() throws Exception {
        Path directory = Files.createDirectories(root.resolve("physical/deep"));
        Path file = Files.writeString(root.resolve("physical/file"), "fixture");
        Files.createSymbolicLink(root.resolve("alias"), Path.of("physical/deep"));
        Files.createSymbolicLink(root.resolve("absolute"), directory);
        for (String name : List.of("physical/file", "alias/../file", "absolute/../file")) {
            Path path = root.resolve(name);
            assertEquals(file.toString(), Fs.real(path.toString()));
            assertEquals(path.toRealPath().toString(), Fs.real(path.toString()));
        }
    }
    
    @Test void missing_suffixes_and_dangling_targets_remain_non_strict() throws Exception {
        Path directory = Files.createDirectory(root.resolve("physical"));
        Files.createSymbolicLink(root.resolve("alias"), directory);
        Files.createSymbolicLink(root.resolve("dangling"), Path.of("physical/missing"));
        assertEquals(directory.resolve("missing/leaf").toString(), Fs.real(root.resolve("alias/missing/leaf").toString()));
        assertEquals(directory.resolve("leaf").toString(), Fs.real(root.resolve("alias/missing/../leaf").toString()));
        assertEquals(directory.resolve("missing/leaf").toString(), Fs.real(root.resolve("dangling/leaf").toString()));
    }
    
    @Test void mutable_alias_results_are_not_cached() throws Exception {
        Path a = Files.createDirectory(root.resolve("a")), b = Files.createDirectory(root.resolve("b"));
        Path link = Files.createSymbolicLink(root.resolve("link"), a);
        assertEquals(a.toString(), Fs.real(link.toString()));
        Files.delete(link);
        Files.createSymbolicLink(link, b);
        assertEquals(b.toString(), Fs.real(link.toString()));
        assertEquals(b.resolve("missing").toString(), Fs.real(link.resolve("missing").toString()));
    }
    
    @Test void loops_and_excessive_symlink_chains_still_fail() throws Exception {
        Path loop = Files.createSymbolicLink(root.resolve("loop"), Path.of("loop"));
        assertThrows(IOException.class, () -> Fs.real(loop.toString()));
        Files.writeString(root.resolve("target"), "fixture");
        for (int i = 40; i >= 0; i--) Files.createSymbolicLink(root.resolve("link" + i), Path.of(i == 40 ? "target" : "link" + (i + 1)));
        assertThrows(IOException.class, () -> Fs.real(root.resolve("link0").toString()));
    }
    
    @Test void unicode_paths_and_missing_leaves_roundtrip() throws Exception {
        Path directory = Files.createDirectories(root.resolve("загрузки/日本語/😀"));
        Path file = Files.writeString(directory.resolve("файл.txt"), "fixture");
        Path link = Files.createSymbolicLink(root.resolve("ссылка"), directory);
        assertEquals(file.toString(), Fs.real(link.resolve("файл.txt").toString()));
        assertEquals(directory.resolve("ещё-нет").toString(), Fs.real(link.resolve("ещё-нет").toString()));
    }
    
}