package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.graalvm.word.WordFactory;
import ru.pukpukov.pwrap.Config;
import com.google.gson.JsonParser;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Real bwrap appearance projection; only fresh public fixtures, never driver I/O. */
public final class NativeProjectionChecks {
    
    private record Blob(String path, byte[] bytes, int mode, long modified) {}
    
    private record View(Map<String, String> text, List<String> symlinks, List<String> hidden, List<Blob> blobs, Map<String, List<String>> listings) {}
    
    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(Fs.parent(path));
        return Files.writeString(path, text);
    }
    
    private static Path store(Config config, Path path) throws Exception {
        return Path.of(Commands.output(List.of(config.nix().nixStore(), "--add", path.toString())).strip());
    }
    
    private static void launch(Config config, Path payload, Path work, List<String> mounts, View view) throws Exception {
        launch(config, payload, work, mounts, List.of("PROJECTION", Json.encode(view).toString()));
    }
    
    private static void launch(Config config, Path payload, Path work, List<String> mounts, List<String> arguments) throws Exception {
        try (var box = new Sandbox(config.platform, new Lifetime(work))) {
            var roots = new TreeSet<>(NixStorage.readClosure(config.platform.path(PlatformConfig.Key.BASE_CLOSURE)));
            roots.addAll(NixStorage.closure(List.of(payload.toString()), config.nix()));
            box.arguments.addAll(Sandbox.readOnly(roots));
            box.arguments.addAll(mounts);
            box.pin(List.of(), "/fixture-home", List.of());
            
            var child = box.start(Commands.join(List.of(payload.toString(), "_payload"), arguments), List.of());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (child.poll() == null) {
                require(System.nanoTime() < deadline, "Projection payload timed out: " + work);
                Thread.sleep(20);
            }
            require(child.waitFor() == 0, "Projection payload failed: " + work);
        }
    }
    
    public static void probe(String document) throws IOException {
        View view = Json.decode(JsonParser.parseString(document), View.class);
        for (var file : view.text.entrySet())
            require(Files.readString(Path.of(file.getKey())).equals(file.getValue()), "Resource text changed: " + file.getKey());
        for (String path : view.symlinks) require(Files.isSymbolicLink(Path.of(path)), "Resource alias lost: " + path);
        for (String path : view.hidden) require(!Files.exists(Path.of(path)), "Unselected resource exposed: " + path);
        for (Blob blob : view.blobs) {
            Path path = Path.of(blob.path);
            require(Arrays.equals(Files.readAllBytes(path), blob.bytes), "Resource bytes changed");
            require(
                ((Number) Files.getAttribute(path, "unix:mode")).intValue() == blob.mode &&
                    Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS) == blob.modified,
                "Resource metadata changed"
            );
            boolean denied = false;
            try { Files.writeString(path, "not allowed"); } catch (IOException expected) { denied = true; }
            require(denied, "Read-only resource writable");
        }
        for (var listing : view.listings.entrySet()) try (var files = Files.list(Path.of(listing.getKey()))) {
            require(files.map(Fs::name).sorted().toList().equals(listing.getValue()), "Resource directory widened");
        }
    }
    
    public static void graphics(Config config, Path payload, Path work) throws Exception {
        Graphics.Snapshot snapshot = Graphics.discover();
        Graphics.Projection view = Graphics.materialize(snapshot, work);
        for (var binding : view.readOnly())
            require(
                !binding.source().equals(binding.destination()) && !binding.source().startsWith("/sys/") &&
                    (!binding.destination().startsWith("/dev/dri/card") || binding.source().startsWith(work.resolve("identity-").toString())),
                "GPU projection reused a live host control path"
            );
        require(
            snapshot.sysfs().keySet().stream().noneMatch(p -> p.endsWith("config") || p.endsWith("resource") || p.endsWith("power/control")),
            "GPU projection includes a control attribute"
        );
        var mounts = new ArrayList<String>();
        for (var binding : view.readOnly()) mounts.addAll(List.of("--ro-bind", binding.source(), binding.destination()));
        for (var binding : snapshot.runtimeFiles()) if (binding.destination().equals("/proc/driver/nvidia/params"))
            mounts.addAll(List.of("--ro-bind", binding.source(), binding.destination()));
        launch(config, payload, work, mounts, List.of("GRAPHICS", Json.encode(snapshot).toString()));
        System.out.println("PASS: actual static GPU identities/sysfs projection, read-only metadata, no primary device access/FDs; no real device bound");
    }
    
    public static void probeGraphics(String document) throws IOException {
        Graphics.Snapshot snapshot = Json.decode(JsonParser.parseString(document), Graphics.Snapshot.class);
        for (String hidden : List.of("/proc/driver/nvidia/suspend", "/proc/driver/nvidia/registry", "/sys/kernel"))
            require(!Files.exists(Path.of(hidden)), "GPU control path exposed");
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            for (Path path : descriptors.toList()) try {
                int mode = ((Number) Files.getAttribute(path, "unix:mode")).intValue();
                if ((mode & 0170000) != 0020000) continue;
                var device = WordFactory.unsigned(((Number) Files.getAttribute(path, "unix:rdev")).longValue());
                int major = Posix.major(device), minor = Posix.minor(device);
                require(
                    snapshot.statOnly().stream().noneMatch(identity -> identity.major() == major && identity.minor() == minor),
                    "Primary identity descriptor inherited"
                );
            } catch (NoSuchFileException exited) { }
        }
        for (var identity : snapshot.statOnly()) {
            Path path = Path.of(identity.destination());
            int mode = ((Number) Files.getAttribute(path, "unix:mode")).intValue();
            var device = WordFactory.unsigned(((Number) Files.getAttribute(path, "unix:rdev")).longValue());
            require(
                (mode & 0170000) == 0020000 && (mode & 07777) == 0 &&
                    Posix.major(device) == identity.major() && Posix.minor(device) == identity.minor(),
                "Synthetic GPU identity changed"
            );
            for (Set<StandardOpenOption> options : List.<Set<StandardOpenOption>>of(
                Set.of(StandardOpenOption.READ), Set.of(StandardOpenOption.WRITE),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            )) {
                boolean denied = false;
                try (var _ = Files.newByteChannel(path, options)) { }
                catch (AccessDeniedException expected) { denied = true; }
                require(denied, "Mode000 synthetic identity could be opened");
            }
            try (var pin = Posix.open(path.toString(), Posix.O_PATH())) {
                for (Path alias : List.of(path, Path.of(pin.path()))) {
                    boolean denied = false;
                    try { Fs.mode(alias, 0666); } catch (IOException expected) { denied = true; }
                    require(denied, "Read-only synthetic GPU identity mode changed");
                }
            }
        }
        for (var entry : snapshot.sysfs().entrySet()) if (entry.getValue().text() != null) {
            Path path = Path.of("/sys", entry.getKey());
            require(Files.readString(path).equals(entry.getValue().text()), "Projected GPU metadata changed");
            boolean denied = false;
            try { Files.writeString(path, "not permitted"); } catch (IOException expected) { denied = true; }
            require(denied, "Projected GPU metadata writable");
        }
        var pci = snapshot.sysfs().keySet().stream().filter(p -> p.matches("bus/pci/devices/[^/]+")).sorted().toList();
        if (!pci.isEmpty()) {
            try (var entries = Files.list(Path.of("/sys/bus/pci/devices"))) {
                require(
                    entries.map(Fs::name).sorted().toList().equals(pci.stream().map(p -> Fs.name(Path.of(p))).toList()),
                    "Unselected PCI devices exposed"
                );
            }
            for (String entry : pci) {
                Path alias = Path.of("/sys", entry);
                require(Files.isSymbolicLink(alias) && Files.isDirectory(alias), "Selected PCI identity alias is broken");
                for (String control : List.of("config", "resource", "resource0", "power/control"))
                    require(!Files.exists(alias.resolve(control)), "PCI alias exposes a control attribute");
            }
        }
        if (Files.exists(Path.of("/proc/driver/nvidia/params")))
            require(!Files.isWritable(Path.of("/proc/driver/nvidia/params")), "NVIDIA params unexpectedly writable");
    }
    
    public static void run(Config config, Path payload, Path work) throws Exception {
        Path selected = Files.createDirectory(work.resolve("selected"));
        Path canonical = Files.createDirectory(work.resolve("canonical"));
        Path theme = Files.createDirectory(work.resolve("theme-source"));
        write(selected.resolve("ordinary"), "visible");
        write(canonical.resolve("leaf"), "aliased");
        write(canonical.resolve("private-sibling"), "not selected");
        Files.createSymbolicLink(selected.resolve("alias"), canonical.resolve("leaf"));
        write(theme.resolve("ordinary"), "theme");
        write(theme.resolve("override"), "old");
        Path replacement = write(work.resolve("replacement"), "new");
        write(work.resolve("selected-similar/private"), "not selected");
        
        var files = new LinkedHashMap<String, String>();
        files.put(selected.resolve("ordinary").toString(), selected.resolve("ordinary").toString());
        files.put(selected.resolve("alias").toString(), canonical.resolve("leaf").toString());
        files.put(work.resolve("outside-alias").toString(), selected.resolve("ordinary").toString());
        files.put(work.resolve("theme-view/ordinary").toString(), theme.resolve("ordinary").toString());
        files.put(work.resolve("theme-view/override").toString(), replacement.toString());
        files.put(work.resolve("theme-view").toString(), theme.toString());
        var mounts = Mounts.resources(files, List.of(), List.of(selected.toString()));
        require(Collections.frequency(mounts, "--ro-bind") == 4, "Resource reuse mount count changed");
        Map<String, String> text = Map.of(
            selected.resolve("ordinary").toString(), "visible", selected.resolve("alias").toString(), "aliased",
            work.resolve("outside-alias").toString(), "visible", work.resolve("theme-view/ordinary").toString(), "theme",
            work.resolve("theme-view/override").toString(), "new"
        );
        launch(
            config, payload, work, Commands.join(List.of("--ro-bind", selected.toString(), selected.toString()), mounts),
            new View(
                text, List.of(selected.resolve("alias").toString()),
                List.of(canonical.resolve("private-sibling").toString(), work.resolve("selected-similar/private").toString()),
                List.of(), Map.of()
            )
        );
        Path fixture = Files.createDirectories(work.resolve("immutable-input/icons"));
        for (int i = 0; i < 3; i++) {
            byte[] data = new byte[256 * (i + 1)];
            for (int j = 0; j < data.length; j++) data[j] = (byte) j;
            Files.write(fixture.resolve(List.of("one", "two", "unselected-secret").get(i)), data);
        }
        Path immutable = store(config, Fs.parent(fixture));
        var immutableFiles = new LinkedHashMap<String, String>();
        var blobs = new ArrayList<Blob>();
        for (String name : List.of("one", "two")) {
            Path path = immutable.resolve("icons").resolve(name);
            immutableFiles.put(path.toString(), path.toString());
            blobs.add(new Blob(
                path.toString(), Files.readAllBytes(path), ((Number) Files.getAttribute(path, "unix:mode")).intValue(),
                Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS)
            ));
        }
        launch(
            config, payload, work, Mounts.resources(immutableFiles, List.of(), List.of()),
            new View(
                Map.of(), List.of(), List.of(immutable.resolve("icons/unselected-secret").toString()), blobs,
                Map.of(immutable.resolve("icons").toString(), List.of("one", "two"))
            )
        );
        appearance(config, payload, work);
        launch(
            config, payload, work, List.of(),
            new View(
                Map.of(), List.of(),
                List.of("/sys/dev/char", "/dev/dri", "/dev/nvidia0", "/dev/nvidiactl", "/proc/driver/nvidia"),
                List.of(), Map.of("/proc/driver", List.of())
            )
        );
        System.out.println("PASS: real appearance/resource mounts preserve aliases/bytes/metadata/RO and hide siblings; baseline hides GPU controls");
    }
    private static Desktop.Appearance select(Path path, Path home) throws Exception {
        var selection = new Desktop.Selection(home.toString(), NixStorage.PROVENANCE);
        selection.tree(path.toString());
        return selection.appearance(new Desktop.Supplement(Map.of(), List.of(), Map.of(), List.of()), Map.of(), "");
    }
    
    private static void appearance(Config config, Path payload, Path work) throws Exception {
        Path mediaInput = work.resolve("media-input");
        write(mediaInput.resolve("share/icons/image.png"), "selected media");
        write(mediaInput.resolve("secret"), "not selected");
        Path media = store(config, mediaInput);
        Path theme = Files.createDirectories(work.resolve("theme-input/share/icons"));
        Files.createSymbolicLink(theme.resolve("external.png"), media.resolve("share/icons/image.png"));
        Files.createSymbolicLink(theme.resolve("relative.png"), Path.of("external.png"));
        Files.createSymbolicLink(theme.resolve("cycle"), Path.of("cycle"));
        Files.createSymbolicLink(theme.resolve("missing"), Path.of("nonexistent"));
        Path view = store(config, work.resolve("theme-input")).resolve("share/icons");
        var discovered = select(view, work.resolve("home"));
        require(discovered.files().equals(Map.of(
            view.toString(), view.toString(),
            media.resolve("share/icons/image.png").toString(), media.resolve("share/icons/image.png").toString()
        )), "Appearance selection widened");
        launch(
            config, payload, work, Mounts.resources(discovered.files(), discovered.links(), List.of()),
            new View(
                Map.of(view.resolve("external.png").toString(), "selected media", view.resolve("relative.png").toString(), "selected media"),
                List.of(), List.of(view.resolve("missing").toString(), media.resolve("secret").toString()), List.of(), Map.of()
            )
        );
        
        Path relayInput = Files.createDirectories(work.resolve("relay-input/share"));
        Files.createSymbolicLink(relayInput.resolve("icon.png"), media.resolve("share/icons/image.png"));
        Files.createSymbolicLink(relayInput.resolve("icon-directory"), media.resolve("share/icons"));
        write(Fs.parent(relayInput).resolve("private-sibling"), "not selected");
        Path relay = store(config, Fs.parent(relayInput));
        Path chain = Files.createDirectories(work.resolve("chain-input/share/icons"));
        Files.createSymbolicLink(chain.resolve("file.png"), relay.resolve("share/icon.png"));
        Files.createSymbolicLink(chain.resolve("directory"), relay.resolve("share/icon-directory"));
        write(chain.resolve("ordinary.png"), "internal media");
        Files.createSymbolicLink(work.resolve("chain-input/alias-to-internal.png"), Path.of("share/icons/ordinary.png"));
        Files.createSymbolicLink(chain.resolve("relative-chain.png"), Path.of("../../alias-to-internal.png"));
        view = store(config, work.resolve("chain-input")).resolve("share/icons");
        discovered = select(view, work.resolve("home"));
        require(
            media.resolve("share/icons/image.png").toString().equals(discovered.files().get(relay.resolve("share/icon.png").toString())) &&
                discovered.links().equals(List.of(new Resources.Binding(
                    media.resolve("share/icons").toString(), relay.resolve("share/icon-directory").toString()
                ))),
            "Appearance alias chain changed"
        );
        launch(
            config, payload, work, Mounts.resources(discovered.files(), discovered.links(), List.of()),
            new View(
                Map.of(
                    view.resolve("file.png").toString(), "selected media",
                    view.resolve("directory/image.png").toString(), "selected media",
                    view.resolve("relative-chain.png").toString(), "internal media"
                ),
                List.of(relay.resolve("share/icon-directory").toString()),
                List.of(relay.resolve("private-sibling").toString(), media.resolve("secret").toString()), List.of(), Map.of()
            )
        );
    }
    
}