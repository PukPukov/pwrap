package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.Endpoint.Port;
import com.google.gson.JsonObject;
import ru.pukpukov.pwrap.Launch;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.Policy.Mode.*;

/** Root system manager and cgroup-v2 boundaries. No sudo/setuid fallback. */
public final class Platform {
    
    public record Unit(Launch.Prepared prepared, Network.Boundary boundary, String unit, Egress.Name table, Port canary) {
        public Unit {
            require(
                unit.matches("pwrap-[a-zA-Z0-9-]+\\.service") && table.value().matches("pwrap_[0-9a-f]{32}"),
            "Invalid cgroup egress identity");
            Objects.requireNonNull(canary);
        }
        
        public Map<String, JsonObject> snapshots() {
            Map<String, JsonObject> values = new TreeMap<>();
            if (prepared.launchSnapshot() != null)
                values.put(Launch.CODEC.key(), encode(prepared.launchSnapshot()).getAsJsonObject());
            values.put(Network.CODEC.key(), encode(new Instances.Snapshot<>(boundary.request(), boundary.capture(), null)).getAsJsonObject());
            return values;
        }
        
        public void register(PlatformConfig config, boolean publish) throws Exception {
            boundary.verify(prepared.policy().network(), config);
            SystemdInstances.HOST.register(
                prepared.policy().name(), unit, Launch.fingerprint(prepared.authority(boundary, config.identity)),
            prepared.session().values(), snapshots(), publish);
        }
    }
    
    @FunctionalInterface public interface BeforeStart {
        boolean activate(Launch.Prepared prepared, Network.Boundary boundary) throws Exception;
    }
    
    public static int run(Launch.Prepared prepared, PlatformConfig config, Path runtimeFile, BeforeStart activate) throws Exception {
        require(Posix.geteuid() == 0, "The nixos platform requires root; no sudo/setuid fallback");
        try (var frozen = Network.freeze(prepared.policy(), config, SystemdInstances.HOST)) {
            return dispatch(prepared, frozen.boundary(), activate, () -> runUnit(prepared, frozen.boundary(), config, runtimeFile));
        }
    }
    
    public static int dispatch(
        Launch.Prepared prepared, Network.Boundary boundary, BeforeStart activate, Callable<Integer> launch
    ) throws Exception {
        return activate.activate(prepared, boundary) ? 0 : launch.call();
    }
    
    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    
    public static String quoted(List<String> command) {
        return command.stream()
            .map(s -> s.matches("[a-zA-Z0-9_@%+=:,./-]+") ? s : "'" + s.replace("'", "'\\''") + "'")
            .collect(Collectors.joining(" "));
    }
    
    static int runUnit(Launch.Prepared prepared, Network.Boundary boundary, PlatformConfig config, Path runtime) throws Exception {
        return runUnit(prepared, boundary, config, runtime, new Lifetime());
    }
    
    static int runUnit(
        Launch.Prepared prepared, Network.Boundary boundary, PlatformConfig config, Path runtime, Lifetime lifetime
    ) throws Exception {
        try (var canary = new ServerSocket(); lifetime) {
            canary.bind(new InetSocketAddress("127.0.0.1", 0), 2);
            Path work = lifetime.temp("pwrap-");
            String unitName = "pwrap-" + prepared.policy().name().replaceAll("[^a-zA-Z0-9]", "-")
                .substring(0, Math.min(40, prepared.policy().name().length())) + "-" + uuid().substring(0, 10);
            Unit unit = new Unit(prepared, boundary, unitName + ".service", new Egress.Name("pwrap_" + uuid()), new Port(canary.getLocalPort()));
            Path manifest = work.resolve("policy.json");
            Files.writeString(manifest, encode(unit).toString());
            Fs.mode(manifest, 0600);
            Policy.Limits limits = required(prepared.policy().limits(), "Managed execution requires cgroup limits");
            List<String> command = Commands.args(
                config.path(SYSTEMD_RUN), "--quiet", "--collect", "--wait",
                Posix.isatty(0) == 1 && Posix.isatty(1) == 1 ? "--pty" : "--pipe",
                "--service-type=exec", "--unit=" + unitName,
                "-p", "KillMode=control-group", "-p", "NoNewPrivileges=yes", "-p", "ProtectControlGroups=yes",
                "-p", "RestrictRealtime=yes", "-p", "UMask=0077", "-p", "TasksMax=" + limits.tasksMax(),
            "-p", "MemoryMax=" + limits.memoryMax(), "-p", "OOMPolicy=kill");
            Policy.Mode mode = prepared.policy().network().mode();
            if (mode == NONE) command.addAll(List.of("-p", "IPAddressDeny=any"));
            if (Set.of(INTERNET, LAN, LOCAL).contains(mode))
                command.addAll(List.of("-p", "ExecStopPost=-" + config.path(NFT) + " delete table inet " + unit.table));
            if (!prepared.policy().name().equals("run"))
                command.addAll(List.of(
                "-p", "ExecStopPost=" + quoted(List.of(
                config.entry, runtime.toString(), "_release", prepared.policy().name(), unit.unit))));
            command.addAll(List.of(config.entry, runtime.toString(), "_launch", manifest.toString()));
            return Commands.call(command);
        }
    }
    
    public static String rules(Unit unit) {
        return Egress.table(
            unit.table,
            Map.of(
            new Egress.Name("restricted"), new Egress.Chain(
            new Egress.Cgroup("system.slice/" + unit.unit), unit.boundary.rules(unit.canary)
            )),
        -174, "pwrap egress");
    }
    
    public static void configure(Unit unit, PlatformConfig config) throws Exception {
        unit.boundary.verify(unit.prepared.policy().network(), config);
        Policy.Mode mode = unit.prepared.policy().network().mode();
        if (Set.of(INTERNET, LAN, LOCAL).contains(mode)) Commands.run(List.of(config.path(NFT), "--file", "-"), rules(unit), true);
        if (mode != HOST) try (var probe = new Socket()) {
            try {
                probe.connect(new InetSocketAddress("127.0.0.1", unit.canary.value()), 300);
            } catch (IOException denied) {
                return;
            }
            throw new IllegalStateException("Network boundary is NOT enforced; refusing to launch");
        }
    }
    
}