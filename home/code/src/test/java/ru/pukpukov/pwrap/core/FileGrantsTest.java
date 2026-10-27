package ru.pukpukov.pwrap.core;

import module java.base;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import static org.junit.jupiter.api.Assertions.*;
import static org.graalvm.nativeimage.c.type.CTypeConversion.toCString;

/** Native descriptor and anchored-mkdir cases from test_file_grants.py. */
@EnabledInNativeImage
class FileGrantsTest {
    
    final Path root;
    Path allowed, protectedDir;
    FileAuthority.Check validate;
    
    FileGrantsTest(@TempDir Path root) throws IOException {
        this.root = root;
        allowed = Files.createDirectory(root.resolve("allowed"));
        protectedDir = Files.createDirectory(root.resolve("controller"));
        validate = FileAuthority.validator("/root", List.of(protectedDir.toString()));
    }
    
    private List<Grant> grants(boolean writable) { return List.of(new Grant(allowed.toString(), "/data", writable)); }
    
    private void mkdir(Path path, Posix.Opener opener) throws Exception {
        validate.check(path.toString(), true);
        try (var _ = FileAuthority.openPath(path.toString(), validate, (parent, name) -> {
            try (var value = toCString(name)) { Posix.check(Posix.mkdirat(parent, value.get(), 0700), "mkdirat"); }
        }, opener)) { }
    }
    
    private static void closed(Collection<Integer> fds) {
        for (int fd : fds) assertThrows(IOException.class, () -> Posix.stat(fd));
    }
    
    @Test void test_preparation_needs_only_anchored_creation_not_runtime_tools() throws Exception {
        String target = allowed.resolve("one/two").toString();
        int[] created = {0};
        var files = new Policy.FileAccess(List.of(), List.of(), List.of(target), List.of(), List.of(), null);
        var grants = FileAuthority.prepare(files, "/session-home", (parent, name) -> {
            try (var value = toCString(name)) { Posix.check(Posix.mkdirat(parent, value.get(), 0700), "mkdirat"); }
            created[0]++;
        });
        assertEquals(List.of(new Grant(target, target, true)), grants);
        assertEquals(2, created[0]);
        assertTrue(Files.isDirectory(Path.of(target)));
    }
    
    @Test void test_pins_expose_immutable_semantic_bindings_not_platform_arguments() throws Exception {
        try (var pins = new FileAuthority.Pins(grants(true), validate)) {
            var bindings = pins.bindings(); var fds = pins.fds();
            assertEquals(grants(true).getFirst(), bindings.getFirst().grant());
            assertSame(fds.getFirst(), bindings.getFirst().fd());
            assertThrows(UnsupportedOperationException.class, bindings::clear);
            assertThrows(UnsupportedOperationException.class, fds::clear);
        }
    }
    
    @Test void test_pin_survives_name_replacement_and_closes_idempotently() throws Exception {
        long original = ((Number) Files.getAttribute(allowed, "unix:ino")).longValue();
        int fd;
        try (var pins = new FileAuthority.Pins(grants(true), validate)) {
            fd = pins.fds().getFirst().n;
            Files.move(allowed, root.resolve("original"));
            Files.createSymbolicLink(allowed, protectedDir);
            assertEquals(original, Posix.stat(fd).ino());
            assertEquals(new FileAuthority.Pinned(grants(true).getFirst(), pins.fds().getFirst()), pins.bindings().getFirst());
            pins.close();
            pins.close();
        }
        closed(List.of(fd));
    }
    
    @Test void test_opened_protected_target_is_denied_after_symlink_race() throws Exception {
        List<Integer> descriptors = new ArrayList<>();
        Posix.Opener raced = (parent, name, flags, mode) -> {
            if (name.equals("allowed")) {
                Files.delete(allowed);
                Files.createSymbolicLink(allowed, protectedDir);
            }
            var fd = Posix.open(parent, name, flags, mode);
            descriptors.add(fd.n);
            return fd;
        };
        assertThrows(IOException.class, () -> new FileAuthority.Pins(grants(true), validate, raced));
        closed(descriptors);
    }
    
    @Test void test_exception_after_acquiring_pins_does_not_leak_descriptors() throws Exception {
        List<Integer> fds = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> {
            try (var pins = new FileAuthority.Pins(grants(false), validate)) {
                fds.add(pins.fds().getFirst().n);
                assertFalse(pins.bindings().getFirst().grant().writable());
                throw new IllegalStateException("setup failed");
            }
        });
        closed(fds);
    }
    
    @Test void test_partial_acquisition_failure_closes_previously_open_grants() {
        List<Integer> fds = new ArrayList<>();
        Posix.Opener remember = (parent, name, flags, mode) -> {
            var fd = Posix.open(parent, name, flags, mode);
            fds.add(fd.n);
            return fd;
        };
        var grants = List.of(
            new Grant(allowed.toString(), "/data", true), new Grant(protectedDir.toString(), "/other", true)
        );
        assertThrows(IllegalArgumentException.class, () -> new FileAuthority.Pins(grants, validate, remember));
        closed(fds);
    }
    
    @Test void test_persist_mkdir_is_anchored_to_open_parent_not_replaced_path() throws Exception {
        boolean[] changed = {false};
        Posix.Opener raced = (parent, name, flags, mode) -> {
            var fd = Posix.open(parent, name, flags, mode);
            if (name.equals("allowed") && !changed[0]) {
                changed[0] = true;
                Files.move(allowed, root.resolve("original"));
                Files.createSymbolicLink(allowed, protectedDir);
            }
            return fd;
        };
        mkdir(allowed.resolve("new/deep"), raced);
        assertFalse(Files.exists(protectedDir.resolve("new")));
        assertTrue(changed[0]);
    }
    
    @Test void test_persist_rejects_protected_existing_or_missing_path() {
        for (Path path : List.of(protectedDir, protectedDir.resolve("new/deep")))
            assertThrows(IllegalArgumentException.class, () -> mkdir(path, Posix::open));
        assertFalse(Files.exists(protectedDir.resolve("new")));
    }
    
    @Test void test_persist_creates_nested_path_without_rejecting_root_ancestor() throws Exception {
        Path target = allowed.resolve("one/two");
        mkdir(target, Posix::open);
        assertTrue(Files.isDirectory(target));
    }
    
    @Test void test_pinned_access_cannot_be_fooled_by_other_inode_at_same_name() throws Exception {
        try (var pins = new FileAuthority.Pins(grants(false), validate)) {
            Files.move(allowed, root.resolve("original"));
            Files.createDirectory(allowed);
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.accessGrants(grants(false), pins.fds()));
        }
    }
    
    @Test void test_pinned_access_records_reference_inherited_descriptors_not_names() throws Exception {
        try (var pins = new FileAuthority.Pins(grants(false), validate)) {
            assertEquals(
                List.of(new Grant("/proc/self/fd/" + pins.fds().getFirst().n, "/data", false)),
                FileAuthority.accessGrants(grants(false), pins.fds())
            );
        }
    }
    
}