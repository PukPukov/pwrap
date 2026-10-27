package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.core.Resources.Binding;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Filesystem projection cases from test_graphics.py; no host driver access. */
class GraphicsTest {
    
    final Path root;
    
    GraphicsTest(@TempDir Path root) { this.root = root; }
    
    private Path write(String name, String value) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(Fs.parent(path));
        return Files.writeString(path, value);
    }
    
    @Test void test_baseline_hides_host_driver_controls_even_without_gpu() {
        List<String> args = Sandbox.baseline(RuntimeFixture.platform(root, obj()));
        int index = args.indexOf("--proc");
        assertEquals(List.of("--proc", "/proc", "--tmpfs", "/proc/driver"), args.subList(index, index + 4));
    }
    
    @Test void test_gpu_default_follows_desktop_but_explicit_opt_out_is_preserved() {
        assertFalse(Policy.read(obj("command", List.of("app"))).integration().gpu());
        assertTrue(Policy.read(obj("command", List.of("app"), "desktop", true)).integration().gpu());
        assertFalse(Policy.read(obj("command", List.of("app"), "desktop", true, "gpu", false)).integration().gpu());
    }
    
    @Test void test_sysfs_projection_selects_identity_not_control_files() throws Exception {
        for (var entry : Map.<String, String>of(
            "vendor", "0x10de", "device", "0x1234", "uevent", "DRIVER=nvidia",
            "config", "secret", "resource", "secret", "power/control", "secret",
            "drm/card1/dev", "226:1", "drm/renderD128/dev", "226:128"
        ).entrySet()) write("devices/pci0/gpu/" + entry.getKey(), entry.getValue());
        for (String name : List.of("card1", "renderD128")) {
            Path node = root.resolve("devices/pci0/gpu/drm/" + name);
            Files.createSymbolicLink(node.resolve("device"), Path.of("../.."));
            for (String prefix : List.of("class/drm", "dev/char")) {
                Path target = root.resolve(prefix).resolve(prefix.equals("class/drm") ? name : Files.readString(node.resolve("dev")));
                Files.createDirectories(Fs.parent(target));
                Files.createSymbolicLink(target, Fs.parent(target).relativize(node));
            }
        }
        write("devices/other/drm/card9/dev", "226:9");
        Files.createSymbolicLink(root.resolve("class/drm/card9"), Path.of("../../devices/other/drm/card9"));
        Path pci = Files.createDirectories(root.resolve("bus/pci/devices"));
        Files.createSymbolicLink(pci.resolve("gpu"), Path.of("../../../devices/pci0/gpu"));
        Files.createSymbolicLink(pci.resolve("other"), Path.of("../../../devices/other"));
        var numbers = List.of(new Graphics.Number(226, 128));
        var snapshot = Graphics.sysfs(root, numbers);
        assertTrue(snapshot.keySet().stream().noneMatch(p -> p.contains("other") || p.contains("card9")));
        assertEquals(new Graphics.Entry("0x10de", null, false), snapshot.get("devices/pci0/gpu/vendor"));
        assertEquals("../../devices/pci0/gpu/drm/card1", Json.required(snapshot.get("dev/char/226:1"), "Expected fixture value").link());
        assertTrue(snapshot.keySet().stream().noneMatch(p -> p.endsWith("config") || p.endsWith("resource") || p.endsWith("power/control")));
        assertEquals(snapshot, Graphics.sysfs(root, numbers));
        assertFalse(Graphics.sysfs(root, List.of()).containsKey("devices/pci0/gpu/drm/card1"));
        var projection = Graphics.materialize(new Graphics.Snapshot(List.of(), List.of(), snapshot, List.of(), List.of()), root.resolve("projection"));
        Path selected = Path.of(projection.readOnly().getFirst().source(), "bus/pci/devices/gpu");
        assertEquals("0x10de", Files.readString(selected.resolve("vendor")));
        assertFalse(Files.exists(Fs.parent(selected).resolve("other")));
        for (String control : List.of("config", "resource", "power/control")) assertFalse(Files.exists(selected.resolve(control)));
    }
    
    @Test void test_sysfs_links_cannot_escape_projection() throws Exception {
        Path link = root.resolve("dev/char/226:128");
        Files.createDirectories(Fs.parent(link));
        Files.createSymbolicLink(link, Path.of("/etc"));
        assertThrows(IllegalArgumentException.class, () -> Graphics.sysfs(root, List.of(new Graphics.Number(226, 128))));
    }
    
    @Test void test_only_exact_configured_egl_files_and_libraries() throws Exception {
        Path library = write("lib/test.so", "");
        Path selected = write("egl/one.json", obj("ICD", obj("library_path", library.toString())).toString());
        write("egl/private", "do not expose");
        Graphics.Egl result = Graphics.egl(List.of(Fs.parent(selected)));
        assertEquals(List.of(new Binding(selected.toString(), selected.toString())), result.files());
        assertEquals(List.of(library.toString()), result.roots());
        Files.writeString(selected, "{bad");
        assertThrows(RuntimeException.class, () -> Graphics.egl(List.of(Fs.parent(selected))));
    }
    
    @Test void test_materialize_contains_no_live_sysfs_binds() throws Exception {
        var devices = List.of(new Binding("/dev/dri/renderD128", "/dev/dri/renderD128"));
        var snapshot = new Graphics.Snapshot(
            List.of(), List.of(),
            Map.of(
                "devices/gpu/vendor", new Graphics.Entry("0x1234", null, false),
                "class/drm/card1", new Graphics.Entry(null, "../../devices/gpu", false)
            ),
            devices, List.of()
        );
        Graphics.Projection result = Graphics.materialize(snapshot, root.resolve("projection"));
        Binding binding = result.readOnly().getFirst();
        assertEquals("/sys", binding.destination());
        assertEquals("0x1234", Files.readString(Path.of(binding.source(), "devices/gpu/vendor")));
        assertTrue(Files.isDirectory(Path.of(binding.source(), "class/drm/card1")));
        assertEquals(devices, result.devices());
        assertTrue(result.readOnly().stream().noneMatch(b -> b.source().startsWith("/sys")));
    }
    
}