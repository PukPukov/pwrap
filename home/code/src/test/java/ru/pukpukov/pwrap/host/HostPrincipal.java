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

/** Anonymous network namespaces only: never changes the real host's addresses/routes. */
final class HostPrincipal {
    
    private record Case(String name, String mode, boolean loopback, boolean lan) {}
    
    private static List<String> inside(long pid, List<String> command) throws IOException {
        return Commands.join(List.of(RuntimeFixture.tool("nsenter"), "--target", Long.toString(pid), "--net", "--"), command);
    }
    
    private static void ip(Runtime runtime, long pid, String... arguments) throws Exception {
        require(Commands.call(inside(pid, Commands.join(
            List.of(runtime.config().platform.path(PlatformConfig.Key.IP)), List.of(arguments)
        ))) == 0, "Cannot configure isolated fixture interface");
    }
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        var children = new ArrayList<Process>();
        Path output = Files.createDirectory(work.resolve("output"));
        try {
            for (String label : List.of("host", "peer")) {
                Process child = start(work, label, List.of(
                    RuntimeFixture.tool("unshare"), "--net", "--", payload.toString(), "_fixture", "HOLD"
                ), Map.of());
                children.add(child);
                Path namespace = Path.of("/proc", Long.toString(child.pid()), "ns/net");
                await(() -> Files.exists(namespace) &&
                    !Files.readSymbolicLink(namespace).equals(Files.readSymbolicLink(Path.of("/proc/self/ns/net"))),
                    "Fixture namespace not ready: " + label
                );
            }
            long host = children.get(0).pid(), peer = children.get(1).pid();
            for (long pid : List.of(host, peer)) {
                ip(runtime, pid, "link", "set", "lo", "up");
                require(Commands.call(inside(pid, List.of(
                    RuntimeFixture.tool("sysctl"), "-q", "-w", "net.ipv6.conf.all.disable_ipv6=0", "net.ipv6.conf.default.disable_ipv6=0"
                ))) == 0, "Fixture IPv6 unavailable");
            }
            ip(runtime, host, "link", "add", "fixture-host", "type", "veth", "peer", "name", "fixture-peer");
            ip(runtime, host, "link", "set", "fixture-peer", "netns", Long.toString(peer));
            for (long pid : List.of(host, peer)) {
                String role = pid == host ? "host" : "peer", suffix = pid == host ? "1" : "2";
                ip(runtime, pid, "link", "set", "fixture-" + role, "up");
                ip(runtime, pid, "address", "add", "10.203.0." + suffix + "/24", "dev", "fixture-" + role);
                ip(runtime, pid, "-6", "address", "add", "fd42:203::" + suffix + "/64", "dev", "fixture-" + role, "nodad");
                // Public-shaped destinations are still wholly inside this
                // fixture's veth: no Internet traffic or real routes involved.
                ip(runtime, pid, "address", "add", "198.18.0." + suffix + "/24", "dev", "fixture-" + role);
                ip(runtime, pid, "-6", "address", "add", "2001:db8:203::" + suffix + "/64", "dev", "fixture-" + role, "nodad");
                Path ready = work.resolve(role + "-ready.json");
                children.add(start(work, role + "-server", inside(pid,
                    List.of(payload.toString(), "_fixture", "LISTEN", ready.toString(), "0.0.0.0", "::")
                ), Map.of()));
                await(() -> Files.exists(ready), "Fixture listener not ready: " + role);
            }
            Path shim = RuntimeFixture.script(
                work.resolve("systemd-run"),
                "exec " + RuntimeFixture.quote(Path.of(
                    runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN)
                )) + " --property=NetworkNamespacePath=/proc/" + host + "/ns/net \"$@\"\n"
            );
            JsonObject raw = runtime.descriptor();
            raw.addProperty("systemdRun", shim.toString());
            raw.add("metadata", obj("network", obj()));
            Runtime isolated = descriptor(work.resolve("isolated-runtime.json"), raw);
            var hostEndpoints = Json.read(work.resolve("host-ready.json"), HostFixtures.Endpoints.class).endpoints();
            var peerEndpoints = Json.read(work.resolve("peer-ready.json"), HostFixtures.Endpoints.class).endpoints();
            var cases = List.of(new Case("lan-only", "local", false, true), new Case("both", "local", true, true),
                new Case("loopback-only", "local", true, false), new Case("remote-lan", "lan", false, true),
                new Case("remote-lan-loopback", "lan", true, true)
            );
            for (Case test : cases) {
                Path manifest = manifest(payload, output, work, test, hostEndpoints, peerEndpoints, test.name);
                require(run(work, test.name, inside(host, isolated.command(List.of("profile", manifest.toString())))) == 0,
                    "Host/peer permission matrix failed: " + test.name + " (" + work + ")"
                );
            }
            // The obsolete implicit-local form must remain invalid, not recreate legacy grants.
            Path invalid = write(
                work.resolve("implicit-local.json"),
                obj("name", "run", "network", "local", "command", List.of(payload.toString(), "_payload", "STATUS")).toString()
            );
            require(run(work, "implicit-local", inside(host, isolated.command(List.of("profile", invalid.toString())))) != 0 &&
                Files.readString(work.resolve("implicit-local.log")).contains("local mode requires"),
                "Implicit local mode restored obsolete authority"
            );
            Path manifest = manifest(payload, output, work, cases.getFirst(), hostEndpoints, peerEndpoints, "dynamic");
            Process held = start(work, "dynamic", inside(host, isolated.command(List.of("profile", manifest.toString()))), Map.of());
            children.add(held);
            await(() -> Files.exists(output.resolve("dynamic.json")), "Dynamic fixture did not become ready");
            require(held.isAlive(), "Dynamic fixture exited before address change");
            ip(runtime, host, "address", "add", "10.203.0.99/24", "dev", "fixture-host");
            ip(runtime, host, "-6", "address", "add", "fd42:203::99/64", "dev", "fixture-host", "nodad");
            write(output.resolve("continue"), "");
            finish(held);
            System.out.println("PASS: isolated host/peer TCP+UDP IPv4+IPv6 principals, " +
                "explicit local/LAN intersections and dynamic host address denial"
            );
        } finally {
            write(output.resolve("continue"), "");
            Collections.reverse(children);
            for (Process child : children) if (child.isAlive()) child.destroy();
            for (Process child : children) if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
        }
    }
    
    private static Path manifest(Path payload, Path output, Path work, Case test,
        List<HostNetwork.Check> host, List<HostNetwork.Check> peer, String label
    ) throws IOException {
        var checks = new ArrayList<HostNetwork.Check>();
        for (var endpoint : host) for (String address : endpoint.address().ipv6() ?
            List.of("::1", "fd42:203::1", "fd42:203::99") :
            List.of("127.0.0.1", "127.0.0.2", "10.203.0.1", "10.203.0.99")
        ) {
            boolean loopback = address.equals("::1") || address.startsWith("127.");
            checks.add(new HostNetwork.Check(new Endpoint.Address(address), endpoint.port(), endpoint.transport(), loopback && test.loopback));
        }
        for (var endpoint : peer) {
            checks.add(new HostNetwork.Check(new Endpoint.Address(endpoint.address().ipv6() ? "fd42:203::2" : "10.203.0.2"),
                endpoint.port(), endpoint.transport(), test.lan
            ));
            checks.add(new HostNetwork.Check(new Endpoint.Address(endpoint.address().ipv6() ? "2001:db8:203::2" : "198.18.0.2"),
                endpoint.port(), endpoint.transport(), !test.mode.equals("local")
            ));
        }
        var policy = obj("name", "run", "network", test.mode, "allowLocalhost", test.loopback, "allowLAN", test.lan,
            "networkConfig", obj("localDeniedPorts", List.of(), "relayServices", List.of()),
            "writable", List.of(output.toString()),
            "command", List.of(payload.toString(), "_payload", "PRINCIPALS", output.toString(), Json.encode(checks).toString(), label)
        );
        return write(work.resolve(label + ".json"), policy.toString());
    }
    
    static void probe(List<String> arguments) throws Exception {
        require(arguments.size() == 3, "PRINCIPALS output checks label");
        Path output = Path.of(arguments.getFirst());
        String label = arguments.get(2);
        if (!label.startsWith("remote-")) {
            require(Files.readString(Path.of("/etc/resolv.conf")).isEmpty(), "Local mode inherited host resolver configuration");
            boolean denied = false;
            try { InetAddress.getAllByName("pwrap-local-fixture.invalid"); }
            catch (UnknownHostException expected) { denied = true; }
            require(denied, "Local mode resolved a non-hosts name");
        }
        probe(arguments.get(1));
        HostFixtures.publish(output.resolve(label + ".json"), Json.encode("checked"));
        if (label.equals("dynamic")) {
            await(() -> Files.exists(output.resolve("continue")), "Dynamic fixture not released");
            probe(arguments.get(1));
            HostFixtures.publish(output.resolve("dynamic-after.json"), Json.encode("checked"));
        }
    }
    
    private static void probe(String document) throws IOException {
        var checks = Json.decode(JsonParser.parseString(document), new TypeToken<List<HostNetwork.Check>>() {});
        // A wildcard UDP server may reply from 127.0.0.1 to a packet sent to
        // 127.0.0.2. Match its held port and echo; do not discard a real reply
        // because of source selection. Exact endpoint tests use connected UDP.
        for (var check : checks) require(HostNetwork.connected(check, false) == check.allowed(), "Principal endpoint permission mismatch: " + check);
    }
}