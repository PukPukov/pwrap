package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Endpoint.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;
import static ru.pukpukov.pwrap.core.Policy.Mode.*;

/** Discovery is frozen per live cohort; prepared intent must still match it exactly. */
public final class Network {
    
    public static final String SLIRP_DNS = "10.0.2.3";
    public static final Instances.Codec<Request, Capture> CODEC = new Instances.Codec<>("network-v1", Request.class, Capture.class);
    
    public record Request(int version, String compiler, Policy.Network network, Policy.NetworkSettings settings) {
        public Request {
            require(version == 1 && compiler != null && network != null && settings != null, "Invalid network boundary request");
        }
    }
    
    public record Capture(List<Port> deniedPorts, String resolverText, List<Address> dnsAddresses) {
        public Capture {
            require(deniedPorts.equals(new ArrayList<>(new TreeSet<>(deniedPorts))), "Invalid network boundary capture");
            require(resolverText.indexOf(0) < 0, "Invalid network boundary capture");
            deniedPorts = List.copyOf(deniedPorts);
            dnsAddresses = List.copyOf(dnsAddresses);
        }
    }
    
    public record Boundary(Request request, Capture capture) {
        public Boundary {
            Policy.Mode mode = request.network.mode();
            require(
                !Set.of(INTERNET, LAN, HOST).contains(mode) || !capture.resolverText.isEmpty() && !capture.dnsAddresses.isEmpty(),
            "Remote boundary requires a captured resolver");
            require(
                !Set.of(INTERNET, LAN, LOCAL).contains(mode) || capture.deniedPorts.containsAll(request.settings.localDeniedPorts()),
            "Network capture omitted declared endpoint exclusions");
            require(
                !Set.of(NONE, LOCAL).contains(mode) || capture.resolverText.isEmpty() && capture.dnsAddresses.isEmpty(),
            "Offline/local boundary must not expose DNS");
            require(
                !Set.of(NONE, HOST).contains(mode) || capture.deniedPorts.isEmpty(),
            "Unfiltered/offline boundary must not carry endpoint rules");
        }
        
        public void verify(Policy.Network intent, PlatformConfig config) {
            require(request.compiler.equals(config.entry), "Network boundary compiler changed");
            require(request.network.equals(intent), "Prepared network intent differs from its boundary");
            require(intent.settings() == null || request.settings.equals(intent.settings()), "Prepared network settings differ from its boundary");
        }
        
        public List<String> rules(Port canary) {
            return Egress.expressions(request.network.routes(), capture.dnsAddresses, capture.deniedPorts, canary, List.of(Port.DNS, Port.DOT));
        }
    }
    
    public record Frozen(Boundary boundary, Instances.Lease<Request, Capture> lease) implements AutoCloseable {
        public void close() throws IOException { lease.close(); }
    }
    
    public static Frozen freeze(Policy policy, PlatformConfig config, Instances instances) throws Exception {
        Policy.Network intent = policy.network();
        var settings = intent.settings() != null ? intent.settings() : config.network();
        return freeze(policy, config, instances, () -> capture(intent.mode(),
            () -> deniedPorts(config, settings, Path.of("/sys/fs/cgroup")),
        () -> Egress.Resolver.read(Path.of("/etc/resolv.conf"))));
    }
    
    static Capture capture(Policy.Mode mode, Instances.Capture<List<Port>> ports, Instances.Capture<Egress.Resolver> resolver) throws Exception {
        List<Port> denied = Set.of(INTERNET, LAN, LOCAL).contains(mode) ? ports.capture() : List.of();
        Egress.Resolver dns = Set.of(INTERNET, LAN, HOST).contains(mode) ? resolver.capture() : new Egress.Resolver("", List.of());
        return new Capture(denied, dns.text(), dns.addresses());
    }
    
    public static Frozen freeze(Policy policy, PlatformConfig config, Instances instances, Instances.Capture<Capture> capture) throws Exception {
        Policy.Network intent = policy.network();
        Request request = new Request(1, config.entry, intent, intent.settings() != null ? intent.settings() : config.network());
        var lease = instances.lease(policy.name(), CODEC, request, capture, false);
        try { return new Frozen(new Boundary(lease.snapshot.request(), lease.snapshot.state()), lease); }
        catch (Exception e) {
            lease.close();
            throw e;
        }
    }
    
    public static List<Port> deniedPorts(PlatformConfig config, Policy.NetworkSettings settings, Path cgroups) throws Exception {
        return deniedPorts(config, settings, cgroups, Commands::output);
    }
    
    public static List<Port> deniedPorts(PlatformConfig config, Policy.NetworkSettings settings, Path cgroups, Commands.Query query) throws Exception {
        Set<Port> ports = new TreeSet<>(settings.localDeniedPorts());
        Set<String> pids = new HashSet<>();
        for (String service : settings.relayServices()) {
            String control = query.output(List.of(
                Path.of(config.path(SYSTEMD_RUN)).resolveSibling("systemctl").toString(),
            "show", service, "-p", "ControlGroup", "--value")).strip();
            if (control.isEmpty()) continue;
            require(control.startsWith("/") && Arrays.stream(control.split("/")).noneMatch(s -> s.equals("..")), "Invalid relay ControlGroup: " + control);
            Path group = cgroups.resolve(control.substring(1));
            if (!Files.exists(group)) continue;
            try (var files = Files.walk(group)) {
                for (Path processes : files.filter(p -> Fs.name(p).equals("cgroup.procs")).toList())
                    try {
                        pids.addAll(Files.readString(processes).lines()
                            .flatMap(s -> Arrays.stream(s.split("\\s+")))
                        .filter(s -> !s.isEmpty()).toList());
                    } catch (NoSuchFileException ignored) { /* A cgroup may exit during discovery. */ }
            }
        }
        if (!pids.isEmpty()) {
            String sockets = query.output(List.of(Path.of(config.path(IP)).resolveSibling("ss").toString(), "-H", "-lntup"));
            for (String row : sockets.lines().toList()) {
                var matches = Pattern.compile("pid=(\\d+)").matcher(row);
                boolean selected = false;
                while (matches.find()) if (pids.contains(required(matches.group(1), "Unexpected relay listener PID: " + row))) selected = true;
                if (!selected) continue;
                String endpoint = row.strip().split("\\s+")[4], port = endpoint.substring(endpoint.lastIndexOf(':') + 1);
                require(port.matches("[0-9]+"), "Unexpected relay listener: " + row);
                ports.add(new Port(Integer.parseInt(port)));
            }
        }
        return List.copyOf(ports);
    }
    
}