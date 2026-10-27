package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Connected-mode matrix uses only owned listeners, never public/VPN endpoints. */
public final class HostNetwork {
    
    public enum Transport { TCP, UDP }
    
    public record Check(Endpoint.Address address, Endpoint.Port port, Transport transport, boolean allowed) {}
    
    static boolean connected(Check check) throws IOException { return connected(check, true); }
    
    static boolean connected(Check check, boolean connectedUdp) throws IOException {
        var target = new InetSocketAddress(check.address.literal(), check.port.value());
        if (check.transport == Transport.TCP) {
            try (var socket = new Socket()) {
                try {
                    socket.connect(target, 500);
                    return true;
                } catch (IOException denied) { return false; }
            }
        }
        try (var socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            byte[] data = "fixture".getBytes(StandardCharsets.US_ASCII);
            try {
                if (connectedUdp) socket.connect(target);
                socket.send(new DatagramPacket(data, data.length, target));
                var received = new DatagramPacket(new byte[32], 32);
                socket.receive(received);
                return received.getPort() == check.port.value() && Arrays.equals(data, Arrays.copyOf(received.getData(), received.getLength()));
            } catch (IOException denied) { return false; }
        }
    }
    
    static void probe(String document) throws IOException {
        var checks = Json.decode(JsonParser.parseString(document), new TypeToken<List<Check>>() {});
        for (Check check : checks) require(connected(check) == check.allowed, "Endpoint permission mismatch: " + check);
    }
    
    public static final class Listeners implements AutoCloseable {
        private final List<Closeable> sockets = new ArrayList<>();
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        final List<Check> endpoints = new ArrayList<>();
        
        public Listeners() throws IOException { this(List.of("127.0.0.1", "::1")); }
        public Listeners(List<String> addresses) throws IOException { this(addresses, 0); }
        
        public Listeners(List<String> addresses, int fixedPort) throws IOException {
            try {
                for (String address : addresses) {
                    var tcp = new ServerSocket(fixedPort, 64, InetAddress.getByName(address));
                    sockets.add(tcp);
                    var port = new Endpoint.Port(tcp.getLocalPort());
                    endpoints.add(new Check(new Endpoint.Address(address), port, Transport.TCP, false));
                    threads.submit(() -> {
                        try {
                            while (!tcp.isClosed()) try (var _ = tcp.accept()) { }
                        } catch (IOException closed) { }
                    });
                    var udp = new DatagramSocket(new InetSocketAddress(address, port.value()));
                    sockets.add(udp);
                    endpoints.add(new Check(new Endpoint.Address(address), port, Transport.UDP, false));
                    threads.submit(() -> {
                        try {
                            while (!udp.isClosed()) {
                                var packet = new DatagramPacket(new byte[128], 128);
                                udp.receive(packet);
                                udp.send(packet);
                            }
                        } catch (IOException closed) { }
                    });
                }
            } catch (IOException error) {
                close();
                throw error;
            }
        }
        
        public List<Check> permission(boolean allowed) { return endpoints.stream().map(e -> new Check(e.address, e.port, e.transport, allowed)).toList(); }
        
        public void close() throws IOException {
            IOException failure = null;
            for (Closeable socket : sockets) try { socket.close(); }
            catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            threads.close();
            if (failure != null) throw failure;
        }
    }
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        try (var allowed = new Listeners(); var excluded = new Listeners()) {
            // The name is obtained from this fixture, not arbitrary payload input.
            String abstractName = "@pwrap-matrix-" + UUID.randomUUID();
            try (var _ = Posix.Unix.server(abstractName, Posix.SOCK_STREAM())) {
                var settings = obj("localDeniedPorts", excluded.endpoints.stream().map(e -> e.port.value()).distinct().toList(),
                "relayServices", List.of());
                for (String mode : List.of("none", "local", "lan", "internet")) {
                    for (boolean loopback : List.of(false, true)) {
                        for (boolean lan : List.of(false, true)) {
                            if ((mode.equals("none") && (loopback || lan)) || (mode.equals("internet") && lan) ||
                            (mode.equals("local") && !loopback && !lan)) continue;
                            var checks = new ArrayList<>(allowed.permission(loopback));
                            checks.addAll(excluded.permission(false));
                            var policy = obj("name", "run", "network", mode, "allowLocalhost", loopback, "allowLAN", lan,
                                "networkConfig", settings,
                            "command", List.of(payload.toString(), "_payload", "NETWORK", Json.encode(checks).toString(), abstractName));
                            String label = mode + "-loopback-" + loopback + "-lan-" + lan;
                            Path manifest = write(work.resolve(label + ".json"), policy.toString());
                            require(run(work, label, runtime.command(List.of("profile", manifest.toString()))) == 0,
                            "Connected-mode matrix failed: " + label + " (" + work + ")");
                        }
                    }
                }
                require(run(work, "invalid-offline-loopback", runtime.command(List.of(
                    "run", "--allow-localhost", "--", payload.toString(), "_payload", "STATUS"
                ))) != 0 && Files.readString(work.resolve("invalid-offline-loopback.log")).contains("allowLocalhost requires"),
                "Offline flag silently enabled networking");
                for (List<String> permission : List.<List<String>>of(List.of("--open-web"), List.of("--dbus-talk", "org.freedesktop.Notifications"))) {
                    String label = permission.getFirst().substring(2);
                    var command = new ArrayList<String>(List.of("run", "--net", "local", "--allow-localhost"));
                    command.addAll(permission);
                    command.addAll(List.of("--", payload.toString(), "_payload", "STATUS"));
                    require(run(work, label, runtime.command(command)) != 0 &&
                        Files.readString(work.resolve(label + ".log")).contains("local-only mode forbids host URL and D-Bus brokers"),
                    "Local-only broker restriction changed");
                }
            }
        }
        installerFailure(runtime, payload, work);
        slirp(runtime, payload, Files.createDirectory(work.resolve("slirp")));
        if ("1".equals(System.getenv("PWRAP_TEST_HTTPS"))) for (String mode : List.of("internet", "lan")) {
            String label = "https-" + mode;
            require(run(work, label, runtime.command(List.of(
                "run", "--net", mode, "--", runtime.config().platform.path(PlatformConfig.Key.TOOLBOX) + "/bin/curl",
                "--noproxy", "*", "--fail", "--silent", "--max-time", "25", "--output", "/dev/null",
                "--write-out", "%{http_code}", "https://example.com"
            ))) == 0 && Files.readString(work.resolve(label + ".log")).strip().endsWith("200"),
            "Optional HTTPS fixture failed: " + mode);
        }
        System.out.println("PASS: real TCP/UDP IPv4/IPv6 mode/loopback/LAN/static-deny matrix, abstract IPC, failed-install gate and slirp boundary");
    }
    private static void installerFailure(Runtime runtime, Path payload, Path work) throws Exception {
        JsonObject raw = runtime.descriptor();
        raw.addProperty("nft", RuntimeFixture.script(work.resolve("failed-installer"), "exit 1\n").toString());
        Runtime failed = descriptor(work.resolve("failed-runtime.json"), raw);
        Path marker = work.resolve("unexpected-start");
        require(run(work, "installer-failure", failed.command(List.of(
            "run", "--net", "lan", "--write", work.toString(), "--", payload.toString(), "_payload", "WRITE",
            marker.toString(), "started"
        ))) != 0 && !Files.exists(marker), "Failed network installer entered the payload");
    }
    private static void slirp(Runtime runtime, Path payload, Path work) throws Exception {
        String name = "slirp-smoke-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var policy = obj("name", name, "network", "internet", "command", List.of(payload.toString(), "_payload", "SLEEP", "30000"));
        Path manifest = write(work.resolve("profile.json"), policy.toString());
        Process child = start(work, "launch", runtime.command(List.of("profile", manifest.toString())), Map.of());
        try {
            await(() -> !slirpProcesses(name).isEmpty(), "No ready slirp fixture: " + work);
            Path hostPid = Files.readSymbolicLink(Path.of("/proc/1/ns/pid"));
            for (Path process : slirpProcesses(name)) {
                String status = Files.readString(process.resolve("status"));
                require(status.contains("NoNewPrivs:\t1") && status.contains("CapEff:\t0000000000000400"), "Unexpected slirp capabilities/NNP");
                require(!Files.readSymbolicLink(process.resolve("ns/pid")).equals(hostPid), "Slirp kept host PID namespace");
                for (String path : List.of(
                    "proc/1", "run/systemd/private", "run/dbus/system_bus_socket", "run/pwrap-instances",
                    "home", "root", "nix/var", "etc/shadow", "proc/driver/nvidia"
                )) require(!Files.exists(process.resolve("root").resolve(path)), "Slirp exposed host path: " + path);
            }
        } finally {
            try (var units = Files.newDirectoryStream(Path.of("/sys/fs/cgroup/system.slice"), "pwrap-" + name + "-*.service")) {
                for (Path unit : units) stop(runtime, Fs.name(unit));
            }
            if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
        }
    }
    private static List<Path> slirpProcesses(String name) throws IOException {
        var found = new ArrayList<Path>();
        try (var processes = Files.newDirectoryStream(Path.of("/proc"), "[0-9]*")) {
            for (Path process : processes) try {
                if (Files.readString(process.resolve("comm")).strip().equals("slirp4netns") &&
                    Files.readString(process.resolve("cgroup")).contains("/pwrap-" + name + "-") &&
                Files.readString(process.resolve("status")).contains("Seccomp:\t2")) found.add(process);
            } catch (NoSuchFileException exited) { }
        }
        return found;
    }
}