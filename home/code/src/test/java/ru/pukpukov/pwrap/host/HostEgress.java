package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nixos.ServiceEgress;
import ru.pukpukov.pwrap.backends.nixos.Egress;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostLifetime.await;

/** Generated service/slice lifecycle: exclusively fixture units and one owned nft table. */
public final class HostEgress {
    
    private record Document(Egress.Name table, Map<Egress.Name, ServiceEgress.Service> policies) {}
    
    private record Fixture(
        Map<String, String> units, List<String> names, Egress.Name table, Document document,
        String dropins, String guard, String compiler, String nft, String systemctl, String fail
    ) {}
    
    private record Report(int uid, String start, Endpoint.Port port, boolean hostApi, boolean resolver) {}
    
    private final Fixture fixture;
    private final Path work;
    
    private HostEgress(Fixture fixture, Path work) {
        this.fixture = fixture;
        this.work = work;
    }
    
    private Commands.Result command(List<String> arguments, boolean check) throws Exception {
        var result = Commands.result(arguments, System.getenv(), true);
        Files.writeString(
            work.resolve("operations.jsonl"),
            obj("argv", arguments, "exit", result.code(), "output", result.output()).toString() + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
        require(!check || result.code() == 0, "Fixture command failed: " + arguments + " (" + work + ")");
        return result;
    }
    
    private void ctl(String... arguments) throws Exception { command(Commands.join(List.of(fixture.systemctl), List.of(arguments)), true); }
    
    private boolean active(String unit) throws Exception { return command(List.of(fixture.systemctl, "is-active", "--quiet", unit), false).code() == 0; }
    
    private int offset(String name) throws IOException {
        Path path = work.resolve(name + ".log");
        return Files.exists(path) ? Files.readAllLines(path).size() : 0;
    }
    
    private @Nullable Report read(String name, int offset) throws IOException {
        Path path = work.resolve(name + ".log");
        if (!Files.exists(path)) return null;
        List<String> lines = Files.readAllLines(path);
        for (int index = lines.size() - 1; index >= offset; index--) {
            if (lines.get(index).startsWith("{")) return Json.decode(JsonParser.parseString(lines.get(index)), Report.class);
        }
        return null;
    }
    
    private Map<String, Report> reports(Map<String, Integer> offsets) throws Exception {
        var reports = new LinkedHashMap<String, Report>();
        for (String name : fixture.names) {
            int offset = offsets.getOrDefault(name, 0);
            await(() -> read(name, offset) != null, "Generated service probe not ready: " + name + " (" + work + ")");
            Report report = required(read(name, offset), "Missing fixture report");
            require(
                !report.hostApi && report.resolver == name.endsWith("root") && (report.uid != 0) == name.endsWith("dynamic"),
                "Generated egress/UID contract changed: " + name + ": " + report
            );
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", report.port.value()), 3000);
                socket.setSoTimeout(3000);
                require(
                    Arrays.equals(socket.getInputStream().readNBytes(5), "reply".getBytes(StandardCharsets.US_ASCII)),
                    "Inbound service reply denied"
                );
            }
            reports.put(name, report);
        }
        return reports;
    }
    
    private Map<String, Integer> offsets() throws IOException {
        var result = new LinkedHashMap<String, Integer>();
        for (String name : fixture.names) result.put(name, offset(name));
        return result;
    }
    
    private void services(String action) throws Exception {
        command(Commands.join(
            List.of(fixture.systemctl, action),
            fixture.names.stream().map(name -> name + ".service").toList()
        ), true);
    }
    
    private Map<String, Long> sliceIds(Collection<? extends String> slices) throws IOException {
        var result = new LinkedHashMap<String, Long>();
        for (String slice : slices) {
            result.put(slice, ((Number) Files.getAttribute(Path.of("/sys/fs/cgroup/system.slice", slice), "unix:ino")).longValue());
        }
        return result;
    }
    
    private static void restarted(Map<String, Report> before, Map<String, Report> after) {
        for (String name : before.keySet()) {
            require(
                !before.get(name).start.equals(required(after.get(name), "Missing restarted probe").start),
                "Observed stale probe after restart"
            );
        }
    }
    
    public static void check(Path nixpkgs, Path source) throws Exception {
        require(Posix.geteuid() == 0 && Files.isRegularFile(source), "Root and generated egress fixture source required");
        Path work = Files.createTempDirectory(Path.of("/var/tmp"), "pwrap-egress-lifecycle-");
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        System.out.println("Evidence: " + work);
        String dns = "127.77." + (1 + Integer.parseInt(token.substring(0, 2), 16) % 254) + "." + (1 + Integer.parseInt(token.substring(2, 4), 16) % 254);
        try (
            var api = new HostNetwork.Listeners(List.of("127.0.0.1"));
            var resolver = new HostNetwork.Listeners(List.of(dns), 53)
        ) {
            String expression = "let pkgs = import (builtins.toPath " + encode(nixpkgs.toRealPath().toString()) +
                ") {}; in import (builtins.toPath " + encode(source.toRealPath().toString()) +
                ") { inherit pkgs; testRoot = " + encode(work.toString()) + "; token = " + encode(token) +
                "; probePort = " + api.endpoints.getFirst().port().value() + "; resolverAddress = " + encode(dns) + "; }";
            Process build = HostChecks.start(work, "build", List.of(RuntimeFixture.tool("nix-build"), "--no-out-link", "--expr", expression), Map.of());
            try {
                require(build.waitFor(20, TimeUnit.MINUTES) && build.exitValue() == 0, "Egress fixture build failed: " + work);
            } finally {
                if (build.isAlive()) build.destroyForcibly().waitFor();
            }
            String result = Files.readString(work.resolve("build.log")).lines()
                .filter(line -> line.startsWith("/nix/store/") && !line.contains(" "))
                .reduce((first, second) -> second).orElseThrow();
            Fixture fixture = Json.read(Path.of(result), Fixture.class);
            require(
                fixture.table.value().equals("pwrap_fixture_" + token) &&
                fixture.guard.equals("pwrapfixture" + token + ".service") &&
                fixture.units.keySet().stream().allMatch(name ->
                    (name.startsWith("pwrapfixture" + token) || name.startsWith("system-pwrap")) &&
                    (name.endsWith(".service") || name.endsWith(".slice"))
                ),
                "Unexpected fixture identity"
            );
            new HostEgress(fixture, work).lifecycle();
        }
    }
    
    private void lifecycle() throws Exception {
        var slices = fixture.units.keySet().stream().filter(name -> name.endsWith(".slice")).toList();
        var files = new ArrayList<Path>();
        var folders = new ArrayList<Path>();
        var owned = new ArrayList<String>();
        var evidence = new ArrayList<JsonObject>();
        boolean loaded = false;
        require(command(List.of(fixture.nft, "list", "table", "inet", fixture.table.value()), false).code() != 0, "Fixture nft identity collides");
        for (String unit : fixture.units.keySet()) {
            String state = command(List.of(fixture.systemctl, "show", "-p", "LoadState", "-p", "FragmentPath", "-p", "ActiveState", unit), true).output();
            require(
                state.lines().anyMatch(line -> line.equals("LoadState=not-found")) ||
                unit.endsWith(".slice") && state.lines().anyMatch(line -> line.equals("FragmentPath=")) &&
                    state.lines().anyMatch(line -> line.equals("ActiveState=inactive")),
                "Fixture unit collides: " + unit
            );
        }
        try {
            for (var unit : fixture.units.entrySet()) {
                Path path = Path.of("/run/systemd/system", unit.getKey());
                Files.writeString(path, Files.readString(Path.of(unit.getValue())), StandardOpenOption.CREATE_NEW);
                files.add(path);
                owned.add(unit.getKey());
            }
            for (String name : fixture.names) {
                Path folder = Path.of("/run/systemd/system", name + ".service.d");
                Files.createDirectory(folder);
                folders.add(folder);
                Path path = folder.resolve("confinement.conf");
                Files.writeString(path, Files.readString(Path.of(fixture.dropins, Fs.name(folder), Fs.name(path))),
                    StandardOpenOption.CREATE_NEW
                );
                files.add(path);
            }
            ctl("daemon-reload");
            loaded = true;
            services("start");
            require(active(fixture.guard), "Egress guard did not start");
            var first = reports(Map.of());
            var ids = sliceIds(slices);
            evidence.add(obj("initial", first, "sliceIds", ids));
            
            var offsets = offsets();
            services("restart");
            var second = reports(offsets);
            restarted(first, second);
            require(ids.equals(sliceIds(slices)), "Leaf restart recreated boundary slice");
            evidence.add(obj("serviceRestart", second, "sliceIdsStable", true));
            
            var invalid = new LinkedHashMap<>(fixture.document.policies);
            var key = invalid.keySet().iterator().next();
            var policy = required(invalid.get(key), "Missing fixture policy");
            invalid.put(key, new ServiceEgress.Service(
                policy.network(), policy.dns(), new Egress.Cgroup(
                    "system.slice/system-pwrapmissing" + UUID.randomUUID().toString().replace("-", "") + ".slice"
                )
            ));
            JsonElement before = JsonParser.parseString(command(
                List.of(fixture.nft, "--json", "list", "table", "inet", fixture.table.value()), true
            ).output());
            Path replacement = Files.writeString(
                work.resolve("invalid-replacement.json"),
                encode(new Document(fixture.table, invalid)).toString()
            );
            require(
                command(List.of(fixture.compiler, "_service-egress", replacement.toString(), fixture.nft), false).code() != 0,
                "Missing cgroup accepted"
            );
            JsonElement after = JsonParser.parseString(command(
                List.of(fixture.nft, "--json", "list", "table", "inet", fixture.table.value()), true
            ).output());
            require(before.equals(after), "Failed replacement changed existing nft table");
            for (String name : fixture.names) require(active(name + ".service"), "Failed replacement stopped service");
            evidence.add(obj("failedReplacementRetainedTable", true));
            
            offsets = offsets();
            ctl("restart", fixture.guard);
            var third = reports(offsets);
            restarted(second, third);
            evidence.add(obj("boundaryRestart", third));
            
            ctl("stop", slices.getFirst());
            await(() -> stopped(), "Payload survived missing boundary slice");
            command(List.of(fixture.nft, "list", "table", "inet", fixture.table.value()), true);
            
            offsets = offsets();
            services("start");
            var fourth = reports(offsets);
            restarted(third, fourth);
            require(
                !required(sliceIds(slices).get(slices.getFirst()), "Missing recreated slice").equals(ids.get(slices.getFirst())),
                "Slice identity did not change"
            );
            evidence.add(obj("sliceRecreated", slices.getFirst(), "probes", fourth));
            
            ctl("stop", fixture.guard);
            await(() -> stopped(), "Dependent did not stop");
            Path folder = Path.of("/run/systemd/system", fixture.guard + ".d");
            Files.createDirectory(folder);
            folders.add(folder);
            Path failure = folder.resolve("fixture-failure.conf");
            Files.writeString(failure, "[Service]\nExecStart=\nExecStart=" + fixture.fail + "\n", StandardOpenOption.CREATE_NEW);
            files.add(failure);
            ctl("daemon-reload");
            offsets = offsets();
            require(
                command(Commands.join(
                    List.of(fixture.systemctl, "start"),
                    fixture.names.stream().map(name -> name + ".service").toList()
                ), false).code() != 0 && stopped(),
                "Failed installer started a dependent"
            );
            for (String name : fixture.names) {
                require(
                    read(name, required(offsets.get(name), "Missing log offset")) == null,
                    "Failed installer entered a payload"
                );
            }
            
            Files.delete(failure);
            files.remove(failure);
            ctl("daemon-reload");
            command(Commands.join(
                List.of(fixture.systemctl, "reset-failed", fixture.guard),
                fixture.names.stream().map(name -> name + ".service").toList()
            ), true);
            services("start");
            evidence.add(obj("installerFailureStartedNoPayload", true, "recovered", reports(offsets)));
            System.out.println("PASS: generated service egress; root/shared UID/DynamicUser, restarts, atomic replacement failure and slice recreation");
        } finally {
            if (!owned.isEmpty()) command(Commands.join(List.of(fixture.systemctl, "stop"), owned), false);
            if (loaded) command(List.of(fixture.nft, "delete", "table", "inet", fixture.table.value()), false);
            for (Path file : files) Files.delete(file);
            Collections.reverse(folders);
            for (Path folder : folders) Files.delete(folder);
            ctl("daemon-reload");
            if (!owned.isEmpty()) command(Commands.join(List.of(fixture.systemctl, "reset-failed"), owned), false);
            Files.writeString(work.resolve("result.json"), encode(evidence).toString());
        }
    }
    
    private boolean stopped() throws Exception {
        if (active(fixture.guard)) return false;
        for (String name : fixture.names) if (active(name + ".service")) return false;
        return true;
    }
    
    static void serve(List<String> arguments) throws Exception {
        require(arguments.size() == 2, "SERVICE api-port resolver-address");
        int api = new Endpoint.Port(Integer.parseInt(arguments.getFirst())).value();
        String dns = new Endpoint.Address(arguments.get(1)).literal();
        boolean hostApi = HostNetwork.connected(new HostNetwork.Check(
            new Endpoint.Address("127.0.0.1"), new Endpoint.Port(api), HostNetwork.Transport.TCP, false
        ));
        boolean resolver = HostNetwork.connected(new HostNetwork.Check(
            new Endpoint.Address(dns), Endpoint.Port.DNS, HostNetwork.Transport.TCP, false
        ));
        try (var listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))) {
            System.out.println(encode(new Report(
                Posix.getuid(), UUID.randomUUID().toString(), new Endpoint.Port(listener.getLocalPort()), hostApi, resolver
            )));
            System.out.flush();
            listener.setSoTimeout(500);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (System.nanoTime() < deadline) try (var client = listener.accept()) {
                client.getOutputStream().write("reply".getBytes(StandardCharsets.US_ASCII));
            } catch (SocketTimeoutException idle) { }
        }
    }
    
}