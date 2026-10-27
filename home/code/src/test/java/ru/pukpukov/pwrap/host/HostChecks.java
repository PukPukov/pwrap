package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.StorageBoundaryChecks;
import ru.pukpukov.pwrap.backends.nixos.NativeProjectionChecks;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.Config;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Host-only integrations: fresh fixture paths/units and normal payloads, never real app data. */
public final class HostChecks {
    
    record Runtime(Path file, Config config) {
        
        List<String> command(List<String> arguments) { return Commands.join(List.of(config.platform.entry, file.toString()), arguments); }
        
        JsonObject descriptor() throws IOException { return Json.read(file); }
        
    }
    
    
    private enum Group {
        BASELINE, NETWORK, SECCOMP, DISCOVERY, STORAGE, LIFETIME, MATRIX, URIS, PROJECTIONS,
        AUTHORITY, PRINCIPALS, RELAY, ENDPOINTS, WAYLAND, GRAPHICS, DBUS, UNICODE
    }
    
    public static void run(Path runner, Path payload, List<String> groups) throws Exception {
        require(Posix.geteuid() == 0, "Host integrations require root to create their own confined units");
        require(Files.isExecutable(runner) && Files.isExecutable(payload), "Missing native test executable");
        Path work = Files.createTempDirectory(Path.of("/var/tmp"), "pwrap-host-test-");
        System.out.println("Evidence: " + work);
        Runtime runtime = runtime(runner, work, obj());
        List<Group> selected = groups.isEmpty() ? List.of(Group.values()) : groups.stream().map(Group::valueOf).distinct().toList();
        for (Group group : selected) switch (group) {
            case BASELINE -> baseline(runtime, payload, work);
            case NETWORK -> network(runtime, payload, work);
            case SECCOMP -> seccomp(runtime, payload, work);
            case DISCOVERY -> discovery(runtime, payload, work);
            case STORAGE -> {
                storage(runner, payload, work);
                StorageBoundaryChecks.run(runtime.config(), Files.createDirectory(work.resolve("storage-substitution")));
            }
            case LIFETIME -> HostLifetime.check(runtime, payload, Files.createDirectory(work.resolve("lifetime")));
            case MATRIX -> HostNetwork.check(runtime, payload, Files.createDirectory(work.resolve("network-matrix")));
            case URIS -> HostUris.check(runtime, payload, Files.createDirectory(work.resolve("uris")));
            case PROJECTIONS -> NativeProjectionChecks.run(runtime.config(), payload, Files.createDirectory(work.resolve("projections")));
            case AUTHORITY -> HostAuthority.check(runtime, payload, Files.createDirectory(work.resolve("authority")));
            case PRINCIPALS -> HostPrincipal.check(runtime, payload, Files.createDirectory(work.resolve("principals")));
            case RELAY -> HostRelayLifetime.check(runtime, payload, Files.createDirectory(work.resolve("relay")));
            case ENDPOINTS -> HostEndpoints.check(runtime, payload, Files.createDirectory(work.resolve("endpoints")));
            case WAYLAND -> HostWayland.check(runtime, payload, Files.createDirectory(work.resolve("wayland")));
            case GRAPHICS -> HostGraphics.check(runtime, payload, Files.createDirectory(work.resolve("graphics")));
            case DBUS -> HostDbus.check(runtime, payload, Files.createDirectory(work.resolve("dbus")));
            case UNICODE -> {
                Path unicode = write(work.resolve("выход/日本語-😀/файл"), "данные-😀");
                require(run(work, "unicode", runtime.command(List.of(
                    "run", "--read", unicode.toString(), "--", payload.toString(), "_payload",
                    "READ", unicode.toString(), "данные-😀"
                ))) == 0, "Unicode path/argv/contents failed through actual launcher");
                System.out.println("PASS: real Unicode path grants, UTF-8 argv and file contents through installed native launcher");
            }
        }
    }
    
    private static Runtime runtime(Path runner, Path work, JsonObject metadata) throws Exception {
        var match = Pattern.compile("/nix/store/[^\\s'\"]+-pwrap-runtime\\.json").matcher(Files.readString(runner));
        require(match.find(), "Not a generated pwrap runner");
        JsonObject descriptor = Json.read(Path.of(required(match.group(), "Missing runtime descriptor")));
        descriptor.addProperty("hostConfigDir", work.resolve("absent-host-config").toString());
        descriptor.add("metadata", metadata);
        return new Runtime(Files.writeString(work.resolve("runtime.json"), descriptor.toString()), new Config(descriptor));
    }
    
    static Process start(Path work, String name, List<String> command, Map<String, String> environment) throws IOException {
        Path log = work.resolve(name + ".log");
        ProcessBuilder builder = Commands.process(command).redirectErrorStream(true)
            .redirectOutput(log.toFile()).redirectInput(Path.of("/dev/null").toFile());
        builder.environment().put("PWRAP_TEST_SECRET", "must-not-leak");
        builder.environment().putAll(environment);
        return builder.start();
    }
    
    static int run(Path work, String name, List<String> command) throws Exception { return run(work, name, command, Map.of()); }
    
    static int run(Path work, String name, List<String> command, Map<String, String> environment) throws Exception {
        Process child = start(work, name, command, environment);
        try {
            require(child.waitFor(30, TimeUnit.SECONDS), "Fixture launch timed out: " + name + " (" + work + ")");
            return child.exitValue();
        }
        finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }
    
    private static void baseline(Runtime runtime, Path payload, Path work) throws Exception {
        Files.writeString(work.resolve("read-only"), "allowed\n");
        Files.writeString(work.resolve("forbidden"), "host only\n");
        Files.createDirectory(work.resolve("writable"));
        var command = List.of(
            "run", "--read", work.resolve("read-only").toString(),
            "--write", work.resolve("writable").toString(), "--",
            payload.toString(), "_payload", "FILES", work.toString()
        );
        require(run(work, "baseline", runtime.command(command)) == 0, "Baseline compartment failed; inspect retained evidence");
        require(Files.readString(work.resolve("writable/proof")).equals("success") &&
            Files.readString(work.resolve("read-only")).equals("allowed\n") &&
            Files.readString(work.resolve("forbidden")).equals("host only\n"),
        "Baseline file grants changed host siblings");
        for (String path : List.of("/", "/run")) {
            require(run(work, path.equals("/") ? "deny-root" : "deny-management",
            runtime.command(List.of("run", "--read", path, "--", payload.toString(), "_payload", "STATUS"))) != 0,
            "Broad grant unexpectedly accepted");
        }
        System.out.println("PASS: baseline files/cap0/NNP/seccomp/descriptor ownership and broad-grant refusal");
    }
    
    private static void network(Runtime runtime, Path payload, Path work) throws Exception {
        try (var listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 2);
            var port = new Endpoint.Port(listener.getLocalPort());
            require(run(work, "network-none", runtime.command(List.of(
                "run", "--", payload.toString(), "_payload", "CONNECT", "127.0.0.1", port.toString(), "DENIED"
            ))) == 0, "Offline boundary allowed an owned host listener");
            require(run(work, "network-loopback", runtime.command(List.of(
                "run", "--net", "local", "--allow-localhost", "--", payload.toString(), "_payload",
                "CONNECT", "127.0.0.1", port.toString(), "ALLOWED"
            ))) == 0, "Explicit loopback permission did not reach the owned listener");
        }
        System.out.println("PASS: actual offline denial and explicit loopback grant to a test-owned listener");
    }
    
    private static void seccomp(Runtime runtime, Path payload, Path work) throws Exception {
        KernelChecks.filter(Path.of(runtime.config.platform.path(PlatformConfig.Key.FILTER)));
        require(run(work, "socket-families", runtime.command(List.of("run", "--", payload.toString(), "_payload", "SOCKETS"))) == 0,
        "Actual socket family BPF regression failed");
        require(run(work, "seccomp", runtime.command(List.of("run", "--", payload.toString(), "_payload", "IOCTLS"))) == 0,
        "Actual BPF ioctl filter regression failed");
        System.out.println("PASS: actual ioctl BPF filter, including high bits; no terminal opened");
    }
    
    private static void discovery(Runtime runtime, Path payload, Path work) throws Exception {
        if (System.getenv("WAYLAND_DISPLAY") == null || System.getenv("XDG_RUNTIME_DIR") == null) {
            System.out.println("SKIP: real desktop discovery (no captured Wayland session)");
            return;
        }
        Path font = write(work.resolve("payload/fonts.conf"), "<?xml version=\"1.0\"?><fontconfig/>");
        var environment = obj(
            "FONTCONFIG_FILE", font.toString(), "FONTCONFIG_PATH", Fs.parent(font).toString(),
            "XDG_CONFIG_HOME", work.resolve("payload/config").toString(),
            "GIO_EXTRA_MODULES", work.resolve("payload/modules").toString()
        );
        var policy = obj("name", "run", "desktop", true, "gpu", false, "environment", environment,
        "command", List.of(payload.toString(), "_payload", "PROVENANCE", work.toString()));
        Path manifest = Files.writeString(work.resolve("discovery.json"), policy.toString());
        require(run(work, "discovery", runtime.command(List.of("profile", manifest.toString()))) == 0, "Payload/host discovery provenance changed");
        System.out.println("PASS: real desktop launch keeps payload environment separate from host discovery grants");
    }
    
    static Path write(Path path, String text) throws IOException {
        Files.createDirectories(Fs.parent(path));
        return Files.writeString(path, text);
    }
    
    static Path store(Runtime runtime, Path path) throws Exception {
        String result = Commands.output(List.of(runtime.config.nix().nixStore(), "--add", path.toString())).strip();
        require(result.startsWith("/nix/store/"), "Fixture store import failed");
        return Path.of(result);
    }
    
    private static void storage(Path runner, Path payload, Path parent) throws Exception {
        Path work = Files.createDirectory(parent.resolve("storage"));
        Runtime initial = runtime(runner, work, obj());
        Path leaf = store(initial, write(work.resolve("leaf"), "public test content\n"));
        Path unrelated = store(initial, write(work.resolve("unrelated"), "public test content\n"));
        Path privateDir = Files.createDirectory(work.resolve("private"));
        Files.createSymbolicLink(privateDir.resolve("back-into-store"), unrelated);
        Path source = work.resolve("source");
        for (String name : List.of("data/one/public", "data/two/public", "private-sibling")) {
            write(source.resolve(name), "public test content\n");
        }
        Files.createSymbolicLink(source.resolve("data/one/next"), Path.of("../two"));
        Files.createSymbolicLink(source.resolve("data/two/back"), Path.of("../one"));
        Files.createSymbolicLink(source.resolve("data/one/leaf"), leaf);
        Files.createSymbolicLink(source.resolve("data/one/out"), privateDir);
        Path immutableSource = store(initial, source);
        Path generation = Files.createDirectories(work.resolve("generation/selected"));
        Files.createSymbolicLink(generation.resolve("directory"), immutableSource.resolve("data/one"));
        Files.createSymbolicLink(generation.resolve("leaf"), leaf);
        write(Fs.parent(generation).resolve("other-app/secret"), "hidden");
        write(Fs.parent(generation).resolve("selected-similar/secret"), "hidden");
        Path immutableGeneration = store(initial, Fs.parent(generation));
        Path selected = immutableGeneration.resolve("selected");
        Path mutable = Files.createDirectory(work.resolve("mutable-profile"));
        Files.createSymbolicLink(mutable.resolve("planted"), unrelated);
        Runtime runtime = runtime(runner, work, obj("home-files", List.of(obj(
            "target", mutable.resolve("settings").toString(),
            "managed", unrelated.toString(), "source", unrelated.toString()
        ))));
        var command = List.of(
            "run", "--read", selected.toString(), "--write", mutable.toString(), "--", payload.toString(),
            "_payload", "STORAGE", selected.toString(), immutableSource.toString(), immutableGeneration.toString(),
            privateDir.toString(), unrelated.toString(), mutable.toString()
        );
        require(run(work, "subtrees", runtime.command(command)) == 0, "Real store subtree/cycle/sibling projection changed");
        Path activation = Files.createDirectory(work.resolve("activation"));
        Files.createSymbolicLink(activation.resolve("home-files"), immutableGeneration);
        write(activation.resolve("private-controller-sibling"), "hidden");
        Path immutableActivation = store(runtime, activation);
        Path controller = Files.createSymbolicLink(work.resolve("controller"), immutableActivation);
        Path app = Files.createDirectory(work.resolve("app"));
        for (String name : List.of("directory", "leaf")) Files.createSymbolicLink(app.resolve(name), selected.resolve(name));
        var aliases = List.of(
            "run", "--write", app.toString(), "--immutable-data",
            controller.resolve("home-files/selected/directory").toString(), "--immutable-data",
            controller.resolve("home-files/selected/leaf").toString(), "--", payload.toString(),
            "_payload", "STORAGE_ALIAS", app.toString(), controller.toString(), immutableActivation.toString(),
            immutableGeneration.toString(), immutableSource.toString()
        );
        require(run(work, "aliases", runtime.command(aliases)) == 0, "Exact controller alias suffixes changed or exposed siblings");
        require(run(work, "mutable-only", runtime.command(List.of(
            "run", "--write", mutable.toString(), "--", payload.toString(), "_payload", "DANGLING",
            mutable.resolve("planted").toString()
        ))) == 0, "Mutable/stale metadata created immutable authority");
        Path ancestor = Files.createDirectories(work.resolve("ancestor/selected"));
        Files.createSymbolicLink(ancestor.resolve("parent"), Path.of(".."));
        write(Fs.parent(ancestor).resolve("private"), "hidden");
        Path immutableAncestor = store(runtime, Fs.parent(ancestor));
        require(run(work, "deny-ancestor", runtime.command(List.of(
            "run", "--read", immutableAncestor.resolve("selected").toString(), "--", payload.toString(), "_payload", "STATUS"
        ))) != 0, "Parent directory dependency widened into a generation");
        require(Files.readString(work.resolve("deny-ancestor.log")).contains("ancestor"),
        "Ancestor rejection did not come from dependency validation");
        System.out.println("PASS: real Nix/bwrap exact subtrees, cyclic links, controller aliases, no siblings/stale authority");
    }
    
}