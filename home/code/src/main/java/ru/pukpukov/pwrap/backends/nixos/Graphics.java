package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.graalvm.word.WordFactory;
import org.checkerframework.checker.nullness.qual.Nullable;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Resources.Binding;
import static ru.pukpukov.pwrap.core.Json.*;
import static org.graalvm.nativeimage.c.type.CTypeConversion.*;

/** Static GPU identities, not a grant to the host's sysfs or primary DRM nodes. */
public final class Graphics {
    
    public record Number(int major, int minor) implements Comparable<Number> {
        public int compareTo(Number other) {
            int c = Integer.compare(major, other.major);
            return c == 0 ? Integer.compare(minor, other.minor) : c;
        }
        
        static Number parse(String value) {
            require(value.matches("[0-9]+:[0-9]+"), "Invalid DRM device number: " + value);
            String[] parts = value.split(":");
            return new Number(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        
        public String toString() { return major + ":" + minor; }
    }
    
    public record Entry(@Nullable String text, @Nullable String link, boolean directory) {
        public Entry { require((text != null ? 1 : 0) + (link != null ? 1 : 0) + (directory ? 1 : 0) == 1, "Invalid sysfs projection entry"); }
    }
    
    public record Identity(String destination, int major, int minor) {}
    
    public record Snapshot(
        List<String> runtimeRoots, List<Binding> runtimeFiles, Map<String, Entry> sysfs,
        List<Binding> devices, List<Identity> statOnly
    ) {
        public Snapshot {
            runtimeRoots = List.copyOf(runtimeRoots);
            runtimeFiles = List.copyOf(runtimeFiles);
            sysfs = Map.copyOf(sysfs);
            devices = List.copyOf(devices);
            statOnly = List.copyOf(statOnly);
        }
    }
    
    public record Projection(List<Binding> readOnly, List<Binding> devices) {}
    
    public record Egl(List<Binding> files, List<String> roots) {}
    
    private record Library(String library_path) {}
    private record EglFile(Library ICD, @Nullable String file_format_version) {}
    
    public static Egl egl(List<Path> directories) throws IOException {
        Set<Binding> files = new TreeSet<>(); Set<String> roots = new TreeSet<>();
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) continue;
            try (var entries = Files.newDirectoryStream(dir, "*.json")) {
                List<Path> sorted = new ArrayList<>();
                entries.forEach(sorted::add);
                Collections.sort(sorted);
                for (Path path : sorted) {
                    Path source = path.toRealPath();
                    require(Files.isRegularFile(source), "EGL configuration is not a regular file: " + path);
                    String library = Json.read(source, EglFile.class).ICD.library_path;
                    require(Path.of(library).isAbsolute(), "NixOS EGL library must be an absolute packaged path: " + path);
                    roots.add(Path.of(library).toRealPath().toString());
                    files.add(new Binding(source.toString(), path.toString()));
                }
            }
        }
        return new Egl(new ArrayList<>(files), new ArrayList<>(roots));
    }
    
    private static String inside(Path root, Path path) {
        require(path.startsWith(root), "GPU sysfs link leaves the sysfs root: " + path);
        return root.relativize(path).toString();
    }
    
    private static void add(Path root, Path path, Map<String, Entry> selected) throws IOException {
        String key = inside(root, path);
        if (Files.isSymbolicLink(path)) {
            Path target = path.toRealPath(); String targetKey = inside(root, target); Path parent = Path.of(key).getParent();
            selected.put(key, new Entry(null, (parent == null ? Path.of("") : parent).relativize(Path.of(targetKey)).toString(), false));
            if (Files.isDirectory(target)) selected.putIfAbsent(targetKey, new Entry(null, null, true));
        } else if (Files.isRegularFile(path)) selected.put(key, new Entry(Files.readString(path), null, false));
    }
    
    public static Map<String, Entry> sysfs(Path sysRoot, Collection<Number> deviceNumbers) throws IOException {
        Path root = sysRoot.toRealPath(); Map<String, Entry> selected = new TreeMap<>();
        for (Number number : new TreeSet<>(deviceNumbers)) {
            Path node = root.resolve("dev/char/" + number);
            add(root, node, selected);
            Path device = node.resolve("device").toRealPath();
            inside(root, device);
            // libpci enumerates this directory; retain only the selected GPU alias.
            add(root, root.resolve("bus/pci/devices").resolve(Fs.name(device)), selected);
            for (String name : List.of(
                "vendor", "device", "subsystem_vendor", "subsystem_device", "revision", "class",
                "uevent", "subsystem", "driver"
            )) add(root, device.resolve(name), selected);
            try (var drm = Files.list(device.resolve("drm"))) {
                for (Path path : drm.sorted().toList()) {
                    if (!Fs.name(path).matches("(card[0-9]+|renderD[0-9]+)")) continue;
                    for (String name : List.of("dev", "uevent", "device", "subsystem")) add(root, path.resolve(name), selected);
                    Number selectedNumber = Number.parse(Files.readString(path.resolve("dev")).strip());
                    add(root, root.resolve("dev/char/" + selectedNumber), selected);
                    add(root, root.resolve("class/drm").resolve(Fs.name(path)), selected);
                }
            }
        }
        if (!deviceNumbers.isEmpty()) {
            for (String module : List.of("nvidia", "nvidia_modeset")) add(root, root.resolve("module/" + module + "/initstate"), selected);
        }
        return Map.copyOf(selected);
    }
    
    public static Snapshot discover() throws IOException {
        Egl egl = egl(List.of(Path.of("/etc/glvnd/egl_vendor.d"), Path.of("/etc/egl/egl_external_platform.d")));
        Set<Path> nodes = new TreeSet<>();
        for (var selector : Map.<String, String>of("/dev/dri", "renderD[0-9]*", "/dev", "nvidia[0-9]*").entrySet()) {
            if (Files.isDirectory(Path.of(selector.getKey()))) {
                try (var entries = Files.newDirectoryStream(Path.of(selector.getKey()), selector.getValue())) { entries.forEach(nodes::add); }
            }
        }
        for (String name : List.of("nvidiactl", "nvidia-uvm", "nvidia-uvm-tools")) nodes.add(Path.of("/dev", name));
        List<Binding> devices = new ArrayList<>(); List<Number> numbers = new ArrayList<>();
        for (Path node : nodes) {
            if (!Files.exists(node)) continue;
            int mode = ((java.lang.Number) Files.getAttribute(node, "unix:mode")).intValue();
            require((mode & 0170000) == 0020000, "GPU device is not a character node: " + node);
            devices.add(new Binding(node.toRealPath().toString(), node.toString()));
            if (Fs.name(Fs.parent(node)).equals("dri")) {
                var rdev = WordFactory.unsigned(((java.lang.Number) Files.getAttribute(node, "unix:rdev")).longValue());
                numbers.add(new Number(Posix.major(rdev), Posix.minor(rdev)));
            }
        }
        Map<String, Entry> tree = sysfs(Path.of("/sys"), numbers); List<Identity> identities = new ArrayList<>();
        for (var entry : new TreeMap<>(tree).entrySet()) {
            if (entry.getKey().matches("devices/.*/drm/card[0-9]+/dev")) {
                Number n = Number.parse(required(entry.getValue().text, "DRM identity requires a text attribute").strip());
                String name = Fs.name(Fs.parent(Path.of(entry.getKey())));
                identities.add(new Identity("/dev/dri/" + name, n.major, n.minor));
            }
        }
        List<Binding> files = new ArrayList<>(egl.files); Path params = Path.of("/proc/driver/nvidia/params");
        Set<String> roots = new TreeSet<>(egl.roots);
        for (String driver : List.of("/run/opengl-driver", "/run/opengl-driver-32")) if (Files.exists(Path.of(driver))) {
            roots.add(driver);
            files.add(new Binding(Fs.real(driver), driver));
        }
        if (Files.exists(params) && devices.stream().anyMatch(b -> b.destination().startsWith("/dev/nvidia"))) {
            files.add(new Binding(params.toString(), params.toString()));
        }
        return new Snapshot(new ArrayList<>(roots), files, tree, devices, identities);
    }
    
    public static Projection materialize(Snapshot snapshot, Path directory) throws IOException {
        Path root = directory.resolve("sys");
        Fs.mkdir(root);
        var entries = new TreeMap<>(snapshot.sysfs);
        for (var item : entries.entrySet()) {
            Path relative = Path.of(item.getKey());
            require(!relative.isAbsolute() && relative.normalize().equals(relative) && !item.getKey().startsWith(".."),
            "Invalid GPU sysfs projection path");
            Entry entry = item.getValue();
            if (entry.link != null) continue;
            Path path = root.resolve(relative);
            Files.createDirectories(Fs.parent(path));
            if (entry.text != null) Files.writeString(path, entry.text); else Files.createDirectories(path);
        }
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.link == null) continue;
            Path path = root.resolve(item.getKey());
            Files.createDirectories(Fs.parent(path));
            Files.createSymbolicLink(path, Path.of(entry.link));
        }
        List<Binding> bindings = new ArrayList<>(List.of(new Binding(root.toString(), "/sys")));
        for (int i = 0; i < snapshot.statOnly.size(); i++) {
            Identity identity = snapshot.statOnly.get(i); Path stub = directory.resolve("identity-" + i);
            try (var path = toCString(stub.toString())) {
                Posix.check(Posix.mknod(path.get(), 0020000, Posix.device(identity.major, identity.minor)), "mknod stat-only GPU identity");
            }
            Fs.mode(stub, 0);
            bindings.add(new Binding(stub.toString(), identity.destination));
        }
        return new Projection(List.copyOf(bindings), snapshot.devices);
    }
    
}