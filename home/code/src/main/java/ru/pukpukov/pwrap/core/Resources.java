package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import static ru.pukpukov.pwrap.core.Json.*;

/** Exact resources: shared mechanics for immutable data and appearance, not shared authority. */
public final class Resources {

    public record Resource(String source, String destination, Set<String> aliases) {
        public Resource { aliases = Set.copyOf(aliases); }
    }
    public record Binding(String source, String destination) implements Comparable<Binding> {
        public List<String> json() { return List.of(source, destination); }
        public int compareTo(Binding other) { int c = source.compareTo(other.source); return c == 0 ? destination.compareTo(other.destination) : c; }
        public static Binding from(List<String> pair) { return new Binding(pair.get(0), pair.get(1)); }
    }
    public record Dependencies(Set<Binding> files, Set<Binding> links) {
        public Dependencies {
            files = Collections.unmodifiableSet(new TreeSet<>(files));
            links = Collections.unmodifiableSet(new TreeSet<>(links));
        }
    }
    @FunctionalInterface public interface Link { @Nullable Resource resolve(String path, String root) throws Exception; }
    @FunctionalInterface public interface Inspect { void inspect(Path path) throws Exception; }
    /** Kernel acquisition boundary; alias walking and policy remain in resolve(). */
    public interface Access {
        Posix.Fd open(String path) throws IOException;
        String physical(Posix.Fd fd) throws IOException;
    }
    public static final Access KERNEL = new Access() {
        public Posix.Fd open(String path) throws IOException { return Posix.open(path, Posix.O_PATH()); }
        public String physical(Posix.Fd fd) throws IOException { return Files.readSymbolicLink(Path.of(fd.path())).toString(); }
    };
    public static @Nullable Resource resolve(String input, Predicate<String> immutable, boolean outside) throws Exception {
        return resolve(input, immutable, outside, KERNEL);
    }

    public static @Nullable Resource resolve(String input, Predicate<String> immutable, boolean outside, Access access) throws Exception {
        String path = Fs.absolute(input);
        if (!outside && !immutable.test(path)) return null;
        Posix.Fd fd;
        try { fd = access.open(path); }
        catch (Posix.Failure e) { if (Set.of(2, 20, 40).contains(e.errno)) return null; throw e; }
        try (fd) {
            String source = access.physical(fd);
            Set<String> aliases = new TreeSet<>();
            if (immutable.test(path)) aliases.add(path);
            Deque<String> remaining = new ArrayDeque<>();
            if (!path.equals(source)) Path.of(path).forEach(p -> remaining.add(p.toString()));
            Path resolved = Path.of("/");
            int links = 0;
            while (!remaining.isEmpty()) {
                String component = remaining.removeFirst();
                if (component.equals(".")) continue;
                if (component.equals("..")) { if (resolved.getParent() != null) resolved = resolved.getParent(); continue; }
                Path candidate = resolved.resolve(component);
                if (!Files.isSymbolicLink(candidate)) { resolved = candidate; continue; }
                require(++links <= 40, "Too many resource alias links: " + path);
                Path target = Files.readSymbolicLink(candidate), expanded = target.isAbsolute() ? target : resolved.resolve(target);
                for (String suffix : remaining) expanded = expanded.resolve(suffix);
                String normalized = expanded.normalize().toString();
                if (!outside && !immutable.test(normalized)) return null;
                if (immutable.test(normalized)) {
                    require(
                        Fs.real(normalized).equals(Fs.real(expanded.toString())),
                        "Non-canonical resource alias suffix: " + expanded
                    );
                    aliases.add(normalized);
                }
                remaining.clear();
                expanded.forEach(p -> remaining.add(p.toString()));
                resolved = Path.of("/");
            }
            if (!outside && !immutable.test(source)) return null;
            if (immutable.test(source)) aliases.add(source);
            Posix.Fd current;
            try { current = access.open(source); }
            catch (Posix.Failure e) { if (e.errno == 2) return null; throw e; }
            try (current) {
                var before = Posix.stat(fd.n); var after = Posix.stat(current.n);
                require(before.dev() == after.dev() && before.ino() == after.ino()
                && (path.equals(source) || resolved.toString().equals(source)), "Resource changed during discovery: " + path);
                for (String alias : aliases) require(Fs.real(alias).equals(source), "Resource changed during discovery: " + path);
            }
            return new Resource(source, path, Set.copyOf(aliases));
        }
    }
    public static List<Path> walk(Path source) throws IOException {
        return entries(source, Set.of()).stream().map(Entry::path).toList();
    }
    private record Entry(Path path, boolean link) {}
    private static List<Entry> entries(Path source, Set<String> visited) throws IOException {
        List<Entry> files = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(source)) {
                    if (visited.stream().anyMatch(root -> !root.equals(source.toString()) && Fs.under(directory.toString(), root)))
                        return FileVisitResult.SKIP_SUBTREE;
                    files.add(new Entry(directory, false));
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!file.equals(source)) files.add(new Entry(file, attributes.isSymbolicLink()));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
    public static Dependencies dependencies(
        List<Resource> resources, Predicate<String> immutable,
        @Nullable Link link, @Nullable Inspect inspect
    ) throws Exception {
        return dependencies(resources, immutable, link, inspect, new HashSet<>());
    }

    /** The caller may share completed walks within ONE discovery and policy class. */
    public static Dependencies dependencies(
        List<Resource> resources, Predicate<String> immutable,
        @Nullable Link link, @Nullable Inspect inspect, Set<String> visited
    ) throws Exception {
        Map<String, String> selected = new LinkedHashMap<>(); resources.forEach(r -> selected.put(r.destination, r.source));
        Set<String> available = new HashSet<>(selected.keySet()), anchors = new HashSet<>(available); anchors.addAll(selected.values());
        Deque<String> pending = new ArrayDeque<>(selected.values()); Set<Binding> files = new TreeSet<>(), aliases = new TreeSet<>();
        Consumer<Resource> add = r -> {
            for (String destination : r.aliases) {
                if (Fs.under(destination, available)) continue;
                boolean directory = Files.isDirectory(Path.of(r.source));
                require(
                    !directory || anchors.stream().noneMatch(root -> root.startsWith(destination + "/") || root.startsWith(r.source + "/")),
                    "Resource link would grant an ancestor directory: " + r.destination
                );
                (directory && !r.source.equals(destination) ? aliases : files).add(new Binding(r.source, destination));
                available.add(destination); anchors.addAll(List.of(destination, r.source));
            }
        };
        resources.forEach(add);
        while (!pending.isEmpty()) {
            String source = pending.pop();
            if (Fs.under(source, visited) || !Files.isDirectory(Path.of(source))) continue;
            visited.add(source);
            for (Entry entry : entries(Path.of(source), visited)) {
                if (inspect != null) inspect.inspect(entry.path);
                if (!entry.link) continue;
                Resource r = link == null ? resolve(entry.path.toString(), immutable, false) : link.resolve(entry.path.toString(), source);
                if (r != null) { add.accept(r); pending.push(r.source); }
            }
        }
        return new Dependencies(files, aliases);
    }
    public static List<Binding> bindings(Map<String, String> files, Collection<String> mounted) throws IOException {
        Map<String, String> selected = new LinkedHashMap<>(); Set<String> roots = new HashSet<>(mounted);
        for (var entry : files.entrySet()) {
            String destination = Fs.under(entry.getKey(), roots) ? entry.getValue() : entry.getKey();
            if (!Fs.under(destination, roots)) selected.put(destination, entry.getValue());
        }
        List<Binding> bindings = new ArrayList<>(), directories = new ArrayList<>();
        var entries = new ArrayList<>(selected.entrySet());
        entries.sort(Comparator.comparingInt(e -> (int) e.getKey().chars().filter(c -> c == '/').count()));
        for (var entry : entries) {
            String source = entry.getValue(), destination = entry.getKey();
            if (directories.stream().anyMatch(p -> destination.startsWith(p.destination + "/")
            && source.equals(p.source + destination.substring(p.destination.length()))) && Fs.real(source).equals(source)) continue;
            Binding b = new Binding(source, destination);
            bindings.add(b);
            if (Files.isDirectory(Path.of(source))) directories.add(b);
        }
        return bindings;
    }
}