package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.tainting.qual.Tainted;
import org.checkerframework.checker.nullness.qual.*;
import com.google.gson.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Root-owned cohorts; pending ownership is a kernel lock, never a PID marker. */
public final class Instances {
    
    /** Platform authority for an execution identity; core owns only cohort state. */
    public interface Executions {
        void validate(String identity);
        boolean active(String identity) throws IOException;
        boolean contains(String identity, long pid) throws IOException;
    }
    
    private final Path base;
    private final Executions executions;
    
    public Instances(Path base, Executions executions) {
        this.base = base;
        this.executions = executions;
    }
    
    private void validate(String identity) {
        require(identity.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"), "Invalid execution identity");
        executions.validate(identity);
    }
    
    public record Snapshot<R extends @NonNull Object, S extends @NonNull Object>(@Tainted R request, @Tainted S state, @Nullable String scratch) {}
    
    public record Codec<R extends @NonNull Object, S extends @NonNull Object>(String key, Class<R> request, Class<S> state) {
        public Codec { require(key.matches("[a-zA-Z0-9-]+"), "Invalid snapshot key"); }
        Snapshot<R, S> read(JsonObject document) {
            Document snapshot = decode(document, Document.class);
            return new Snapshot<>(decode(snapshot.request, request), decode(snapshot.state, state), snapshot.scratch);
        }
    }
    
    @FunctionalInterface public interface Capture<T> { T capture() throws Exception; }
    
    // Keep the legacy wire field "unit"; its meaning/validation belong to Executions.
    private record Record(String unit, String fingerprint, Map<String, String> launchEnvironment, @Nullable Map<String, JsonObject> snapshots) {
        // Old on-disk records may lack snapshots. Only an actually dead execution
        // permits discarding them; a live legacy instance must still fail closed.
        Map<String, JsonObject> requireSnapshots() {
            return required(snapshots, "Close the existing instance " + unit + " before changing its snapshot runtime");
        }
    }
    
    private @Nullable Path directory(String name) throws IOException {
        if (name.equals("run")) return null;
        require(name.matches("[a-zA-Z0-9_-]+"), "Invalid application name");
        Path root = base.resolve(name);
        Fs.mkdir(root);
        return root;
    }
    
    private Posix.Fd lock(Path root) throws IOException {
        Posix.Fd fd = Posix.open(root.resolve("lock").toString(), Posix.O_CREAT() | Posix.O_RDWR());
        try {
            fd.lock(false);
            return fd;
        } catch (IOException e) {
            fd.close();
            throw e;
        }
    }
    
    private static List<Path> glob(Path root, String pattern) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root, pattern)) { entries.forEach(files::add); }
        Collections.sort(files);
        return files;
    }
    
    private List<Record> live(Path root) throws IOException {
        List<Record> result = new ArrayList<>();
        for (Path path : glob(root, "*.json")) {
            Record record = Json.read(path, Record.class);
            String unit = record.unit;
            validate(unit);
            require(Fs.name(path).equals(unit + ".json"), "Invalid pwrap instance identity record: " + path);
            if (!executions.active(unit)) {
                Files.delete(path);
                continue;
            }
            record.requireSnapshots();
            result.add(record);
        }
        return result;
    }
    
    private record Document(JsonObject request, JsonObject state, @Nullable String scratch) {
        public Document {
            Objects.requireNonNull(request);
            Objects.requireNonNull(state);
        }
    }
    
    private static void validateSnapshot(JsonObject document) { decode(document, Document.class); }
    
    private List<JsonObject> snapshots(Path root, @Nullable String key) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        for (Record record : live(root)) {
            var snapshots = record.requireSnapshots();
            require(key == null || snapshots.containsKey(key), "Close the existing instance before changing its snapshot runtime");
            var selected = key == null ? snapshots.values() : List.of(required(snapshots.get(key), "Missing required instance snapshot: " + key));
            for (JsonObject snapshot : selected) {
                validateSnapshot(snapshot);
                result.add(snapshot);
            }
        }
        for (Path path : glob(root, ".snapshot-" + (key == null ? "" : key + "-") + "*.lease")) {
            try (var fd = Posix.open(path.toString(), Posix.O_RDWR() | Posix.O_NOFOLLOW())) {
                try {
                    fd.lock(true);
                    Files.delete(path);
                } catch (Posix.Failure e) {
                    if (e.errno != 11) throw e;
                    JsonObject snapshot = read(Path.of(fd.path()));
                    validateSnapshot(snapshot);
                    result.add(snapshot);
                }
            }
        }
        return result;
    }
    
    private void cleanup(Path root) throws IOException {
        Set<String> active = new HashSet<>();
        for (JsonObject snapshot : snapshots(root, null)) {
            String scratch = decode(snapshot, Document.class).scratch;
            if (scratch != null) active.add(scratch);
        }
        for (Path path : glob(root, ".scratch-*")) if (!active.contains(path.toString())) Fs.deleteTree(path);
    }
    
    public final class Lease<R extends @NonNull Object, S extends @NonNull Object> implements AutoCloseable {
        private final Closeable release;
        public final Snapshot<R, S> snapshot;
        
        private Lease(Snapshot<R, S> snapshot, Closeable release) {
            this.snapshot = snapshot;
            this.release = release;
        }
        public void close() throws IOException { release.close(); }
        
    }
    
    public <R extends @NonNull Object, S extends @NonNull Object> Lease<R, S> lease(
        String name, Codec<R, S> codec, R request,
        Capture<S> capture, boolean scratch
    ) throws Exception {
        Path root = directory(name);
        if (root == null) {
            require(!scratch, "Shared scratch requires a named profile");
            return new Lease<>(new Snapshot<>(request, capture.capture(), null), () -> {});
        }
        Path path = root.resolve(".snapshot-" + codec.key + "-" + UUID.randomUUID().toString().replace("-", "") + ".lease");
        Posix.Fd handle = null;
        try (var ignored = lock(root)) {
            cleanup(root);
            Snapshot<R, S> chosen = null;
            for (JsonObject document : snapshots(root, codec.key)) {
                Snapshot<R, S> value = codec.read(document);
                require(value.request.equals(request), "Close the existing " + name + " instance before changing its snapshot request");
                require(chosen == null || value.equals(chosen), "Inconsistent live instance snapshots: " + name);
                chosen = value;
            }
            if (chosen == null) {
                S captured = capture.capture();
                String folder = scratch ? root.resolve(".scratch-" + UUID.randomUUID().toString().replace("-", "")).toString() : null;
                if (folder != null) Fs.mkdir(Path.of(folder));
                chosen = new Snapshot<>(request, captured, folder);
            }
            if (scratch) {
                require(chosen.scratch != null, "Invalid shared scratch directory");
                Path folder = Path.of(chosen.scratch);
                require(
                    root.equals(folder.getParent()) && Fs.name(folder).matches("\\.scratch-[0-9a-f]{32}") &&
                    !Files.isSymbolicLink(folder) && Files.isDirectory(folder),
                    "Invalid shared scratch directory"
                );
            }
            handle = Posix.open(path.toString(), Posix.O_RDWR() | Posix.O_CREAT() | Posix.O_EXCL() | Posix.O_NOFOLLOW());
            handle.lock(false);
            handle.write(canonical(encode(chosen)).toString().getBytes(StandardCharsets.UTF_8));
            Posix.Fd owned = handle;
            return new Lease<>(chosen, () -> {
                try (var ignoredLeaseLock = lock(root)) {
                    Files.deleteIfExists(path);
                    cleanup(root);
                } finally { owned.close(); }
            });
        } catch (Exception e) {
            if (handle != null) handle.close();
            try (var ignored = lock(root)) {
                Files.deleteIfExists(path);
                cleanup(root);
            } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    
    public void register(String name, @Nullable String unit, String fingerprint,
        Map<String, String> environment, Map<String, JsonObject> snapshots, boolean publish
    ) throws IOException {
        Path root = directory(name);
        if (root == null) return;
        if (publish) validate(required(unit, "Invalid instance identity"));
        try (var ignored = lock(root)) {
            for (Record record : live(root)) require(
                record.fingerprint.equals(fingerprint),
                "Close the existing " + name + " instance before changing its package/permissions: " + record.unit
            );
            if (publish && unit != null) {
                Path path = root.resolve(unit + ".json");
                Files.writeString(path, canonical(encode(new Record(unit, fingerprint, Map.copyOf(environment), Map.copyOf(snapshots)))).toString());
                Fs.mode(path, 0600);
            }
        }
    }
    
    public void release(String name, String unit) throws IOException {
        validate(unit);
        Path root = directory(name);
        if (root == null) return;
        try (var ignored = lock(root)) {
            Files.deleteIfExists(root.resolve(unit + ".json"));
            cleanup(root);
        }
    }
    
    public boolean processInProfile(String name, long pid) throws IOException {
        require(pid > 0, "Invalid peer PID");
        Path root = directory(name);
        if (root == null) return false;
        try (var ignored = lock(root)) {
            for (Record record : live(root)) if (executions.contains(record.unit, pid)) return true;
            return false;
        }
    }
    
}