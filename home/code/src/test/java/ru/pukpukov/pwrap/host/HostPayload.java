package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.NativeProjectionChecks;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Normal, bounded fixture operations inside a test compartment; no Python/JVM needed. */
public final class HostPayload {
    
    private enum Probe {
        STATUS, FILES, CONNECT, WRITE, SLEEP, URL, ENVIRONMENT, STORAGE, STORAGE_ALIAS, DANGLING,
        IOCTLS, PROVENANCE, COHORT, SESSION, WATCH, NETWORK, ARGV, PROJECTION, READ, PINNED,
        PRINCIPALS, RELAY, ENDPOINTS, WAYLAND, MAINTENANCE, GRAPHICS, DBUS, SOCKETS
    }
    
    private enum Access { ALLOWED, DENIED }
    
    public static void run(List<String> arguments) throws Exception {
        require(!arguments.isEmpty(), "Missing host-test payload operation");
        switch (Probe.valueOf(arguments.getFirst())) {
            case STATUS -> {
                require(arguments.size() == 1, "STATUS arguments");
                status();
            }
            case PRINCIPALS -> HostPrincipal.probe(arguments.subList(1, arguments.size()));
            case RELAY -> HostRelayLifetime.probe(arguments.subList(1, arguments.size()));
            case ENDPOINTS -> {
                require(arguments.size() == 2, "ENDPOINTS checks");
                HostNetwork.probe(arguments.get(1));
            }
            case WAYLAND -> HostWayland.probe(arguments.subList(1, arguments.size()));
            case DBUS -> HostDbus.probe(arguments.subList(1, arguments.size()));
            case SOCKETS -> {
                require(arguments.size() == 1, "SOCKETS arguments");
                status();
                KernelChecks.socketArguments();
            }
            case MAINTENANCE -> {
                require(arguments.size() == 6 && arguments.get(4).equals("one argument") && arguments.get(5).isEmpty(), "MAINTENANCE argv changed");
                status();
                KernelChecks.socketArguments();
                Path root = Path.of(arguments.get(1)), selected = Path.of(arguments.get(2));
                require(!Files.exists(root.resolve("victim")) &&
                    "explicit".equals(System.getenv("PWRAP_MAINTENANCE_ALLOWED")) &&
                    System.getenv("PWRAP_MAINTENANCE_SECRET") == null,
                    "File task leaked sibling/environment authority"
                );
                Path path = selected.resolve("canary");
                require(Files.readString(path).equals("original"), "File task read changed canary");
                if (arguments.get(3).equals("true")) Files.writeString(path, "only-original-changed");
                else {
                    require(arguments.get(3).equals("false"), "Unknown maintenance mode");
                    boolean denied = false;
                    try { Files.writeString(path, "wrong"); }
                    catch (IOException expected) { denied = true; }
                    require(denied, "Read-only generated task writable");
                }
            }
            case FILES -> {
                require(arguments.size() == 2, "FILES root");
                files(Path.of(arguments.get(1)));
            }
            case IOCTLS -> {
                require(arguments.size() == 1, "IOCTLS arguments");
                status();
                Ioctls.check();
                System.out.println("PASS: plain/high-bit ioctl filtering without a terminal");
            }
            case ARGV -> {
                require(arguments.size() >= 2, "ARGV output [arguments...]");
                status();
                Files.writeString(Path.of(arguments.get(1)), Json.encode(arguments.subList(2, arguments.size())).toString());
            }
            case PROJECTION -> {
                require(arguments.size() == 2, "PROJECTION view");
                NativeProjectionChecks.probe(arguments.get(1));
            }
            case GRAPHICS -> {
                require(arguments.size() == 2, "GRAPHICS snapshot");
                NativeProjectionChecks.probeGraphics(arguments.get(1));
            }
            case READ -> {
                require(arguments.size() == 3, "READ path expected");
                require(Files.readString(Path.of(arguments.get(1))).equals(arguments.get(2)), "Selected contents changed");
            }
            case PINNED -> {
                require(arguments.size() == 2 && Set.of("true", "false").contains(arguments.get(1)), "PINNED writable");
                status();
                Path path = Path.of("/data/canary");
                require(Files.readString(path).equals("original"), "Pinned directory selected a replacement");
                if (Boolean.parseBoolean(arguments.get(1))) Files.writeString(path, "only-original-changed");
                else {
                    boolean denied = false;
                    try { Files.writeString(path, "wrong"); }
                    catch (IOException expected) { denied = true; }
                    require(denied, "Pinned read-only mount writable");
                }
            }
            case NETWORK -> {
                require(arguments.size() == 3, "NETWORK checks abstract-name");
                HostNetwork.probe(arguments.get(1));
                boolean denied = false;
                try (var _ = Posix.Unix.connect(arguments.get(2), Posix.SOCK_STREAM(), 500)) { }
                catch (Posix.Failure failure) {
                    require(failure.errno == 1 || failure.errno == 111, "Unexpected abstract socket error");
                    denied = true;
                }
                require(denied, "Host abstract IPC unexpectedly available");
                status();
            }
            case COHORT, SESSION -> {
                require(arguments.size() == 3, "COHORT/SESSION output mode");
                Path output = Path.of(arguments.get(1));
                boolean first = arguments.get(2).equals("first");
                if (Probe.valueOf(arguments.getFirst()) == Probe.COHORT) {
                    Path state = Path.of("/tmp/shared-state");
                    if (first) Files.writeString(state, "first cohort");
                    else require(Files.readString(state).equals("first cohort"), "Second window lost shared scratch");
                    Files.writeString(output.resolve(first ? "ready" : "second"), "");
                } else Files.writeString(output.resolve(arguments.get(2)), required(System.getenv("TERM"), "Missing captured terminal environment"));
                if (first) waitFor(output.resolve("finish"));
            }
            case WATCH -> {
                require(arguments.size() == 3, "WATCH output port");
                Path output = Path.of(arguments.get(1));
                int port = new Endpoint.Port(Integer.parseInt(arguments.get(2))).value();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                int count = 0;
                while (!Files.exists(output.resolve("finish"))) {
                    require(System.nanoTime() < deadline, "Unbounded watch fixture");
                    try (var socket = new Socket()) {
                        boolean connected = false;
                        try {
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                            connected = true;
                        }
                        catch (IOException denied) { }
                        if (connected) {
                            Files.writeString(output.resolve("unexpected"), "host listener reachable");
                            throw new IllegalStateException("Host listener unexpectedly reachable");
                        }
                    }
                    Files.writeString(output.resolve("heartbeat"), Integer.toString(++count));
                    Thread.sleep(100);
                }
            }
            case PROVENANCE -> {
                require(arguments.size() == 2, "PROVENANCE root");
                Path payload = Path.of(arguments.get(1), "payload");
                var expected = Map.of(
                    "FONTCONFIG_FILE", payload.resolve("fonts.conf").toString(), "FONTCONFIG_PATH", payload.toString(),
                    "XDG_CONFIG_HOME", payload.resolve("config").toString(), "GIO_EXTRA_MODULES", payload.resolve("modules").toString()
                );
                for (var entry : expected.entrySet())
                    require(entry.getValue().equals(System.getenv(entry.getKey())), "Payload environment changed: " + entry.getKey());
                require(!Files.exists(payload.resolve("fonts.conf")) && !Files.exists(payload.resolve("modules")),
                    "Payload environment minted host discovery authority"
                );
            }
            case STORAGE -> {
                require(arguments.size() == 7, "STORAGE selected source generation private unrelated mutable");
                storage(arguments.subList(1, arguments.size()).stream().map(Path::of).toList());
            }
            case STORAGE_ALIAS -> {
                require(arguments.size() == 6, "STORAGE_ALIAS app controller activation generation source");
                aliases(arguments.subList(1, arguments.size()).stream().map(Path::of).toList());
            }
            case DANGLING -> {
                require(arguments.size() == 2, "DANGLING path");
                Path path = Path.of(arguments.get(1));
                require(Files.isSymbolicLink(path) && !Files.exists(path), "Mutable link granted its unselected target");
            }
            case CONNECT -> {
                require(arguments.size() == 4, "CONNECT address port access");
                var address = new Endpoint.Address(arguments.get(1));
                var port = new Endpoint.Port(Integer.parseInt(arguments.get(2)));
                boolean connected = false;
                try (var socket = new Socket()) {
                    try {
                        socket.connect(new InetSocketAddress(address.literal(), port.value()), 750);
                        connected = true;
                    }
                    catch (IOException denied) { }
                }
                require(connected == (Access.valueOf(arguments.get(3)) == Access.ALLOWED), "Unexpected endpoint access: " + address + ":" + port);
            }
            case WRITE -> {
                require(arguments.size() == 3, "WRITE path text");
                Files.writeString(Path.of(arguments.get(1)), arguments.get(2));
            }
            case SLEEP -> {
                require(arguments.size() == 2, "SLEEP milliseconds");
                long duration = Long.parseLong(arguments.get(1));
                require(duration > 0 && duration <= 30_000, "Unbounded fixture sleep");
                Thread.sleep(duration);
            }
            case URL -> {
                require(arguments.size() == 4, "URL opener uri access");
                int result = Commands.call(List.of(arguments.get(1), arguments.get(2)));
                require((result == 0) == (Access.valueOf(arguments.get(3)) == Access.ALLOWED), "Unexpected URI permission");
            }
            case ENVIRONMENT -> {
                require(arguments.size() == 3, "ENVIRONMENT variable expected");
                require(arguments.get(2).equals(System.getenv(arguments.get(1))), "Explicit environment value changed");
                require(System.getenv("PWRAP_TEST_SECRET") == null, "Undeclared host environment leaked");
            }
        }
    }
    
    private static void waitFor(Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(marker)) {
            require(System.nanoTime() < deadline, "Unbounded held fixture");
            Thread.sleep(50);
        }
    }
    
    private static void storage(List<Path> paths) throws IOException {
        Path selected = paths.get(0), source = paths.get(1), generation = paths.get(2);
        Path privateDir = paths.get(3), unrelated = paths.get(4), mutable = paths.get(5);
        for (String name : List.of("directory/public", "directory/next/public", "directory/next/back/public", "directory/leaf"))
            require(Files.readString(selected.resolve(name)).equals("public test content\n"), "Selected immutable contents changed: " + name);
        for (Path hidden : List.of(
            source.resolve("private-sibling"), generation.resolve("other-app"),
            generation.resolve("selected-similar"), privateDir, unrelated
        ))
            require(!Files.exists(hidden), "Unselected immutable sibling visible: " + hidden);
        require(Files.isSymbolicLink(mutable.resolve("planted")) && !Files.exists(mutable.resolve("planted")),
            "Mutable profile expanded immutable authority"
        );
    }
    
    private static void aliases(List<Path> paths) throws IOException {
        Path app = paths.get(0), controller = paths.get(1), activation = paths.get(2), generation = paths.get(3), source = paths.get(4);
        for (Path leaf : List.of(app.resolve("directory/next/public"), app.resolve("leaf"), controller.resolve("home-files/selected/directory/public")))
            require(Files.readString(leaf).equals("public test content\n"), "Exact controller alias no longer resolves");
        for (Path hidden : List.of(activation.resolve("private-controller-sibling"), generation.resolve("other-app"), source.resolve("private-sibling")))
            require(!Files.exists(hidden), "Controller alias promoted a containing generation: " + hidden);
    }
    
    private static void status() throws IOException {
        String status = Files.readString(Path.of("/proc/self/status"));
        require(Posix.getuid() == 0, "Payload namespace UID is not root");
        for (String field : List.of("CapEff", "CapPrm", "CapBnd", "CapAmb"))
            require(status.lines().anyMatch(s -> s.matches(field + ":\\s*0000000000000000")), "Payload kept capabilities: " + field);
        require(status.lines().anyMatch(s -> s.matches("NoNewPrivs:\\s*1")) && status.lines().anyMatch(s -> s.matches("Seccomp:\\s*2")),
            "Payload lacks NNP/seccomp"
        );
        try (var descriptors = Files.list(Path.of("/proc/self/fdinfo"))) {
            for (Path descriptor : descriptors.toList()) try {
                for (String line : Files.readAllLines(descriptor)) if (line.startsWith("flags:"))
                    require((Long.parseLong(line.substring(6).strip(), 8) & 0x200000) == 0, "Host O_PATH descriptor leaked into payload");
            } catch (NoSuchFileException exited) { /* The directory iterator owns a transient descriptor. */ }
        }
        require(System.getenv("PWRAP_TEST_SECRET") == null, "Host environment leaked");
        System.out.println("PASS: cap0, NNP, seccomp, no host O_PATH descriptors");
    }
    
    private static void files(Path root) throws IOException {
        status();
        Path read = root.resolve("read-only"), write = root.resolve("writable/proof");
        require(Files.readString(read).equals("allowed\n"), "Selected read-only contents changed");
        require(!Files.exists(root.resolve("forbidden")), "Ungrantable sibling exposed");
        boolean denied = false;
        try { Files.writeString(read, "wrong"); }
        catch (IOException expected) { denied = true; }
        require(denied, "Read-only grant became writable");
        Files.writeString(write, "success");
        for (String path : List.of(
            "/etc/shadow", "/etc/nix/nix.conf", "/nix/var/nix/daemon-socket/socket", "/run/dbus/system_bus_socket",
            "/run/systemd/private", "/run/docker.sock", "/run/pulse/native", "/run/pipewire/pipewire-0"
        ))
            require(!Files.exists(Path.of(path)), "Ungranted management endpoint exposed: " + path);
        System.out.println("PASS: exact file grants and hidden host endpoints");
    }
    
}