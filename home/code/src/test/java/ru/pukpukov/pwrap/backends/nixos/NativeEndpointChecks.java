package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.Config;
import com.google.gson.*;
import ru.pukpukov.pwrap.Launch;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.host.HostNetwork;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Fixed DNS listeners only inside a fresh anonymous netns, never the real resolver. */
public final class NativeEndpointChecks {
    
    public static void run(Path runtime, Path payload, Path work) throws Exception {
        require(
            !Files.readSymbolicLink(Path.of("/proc/self/ns/net")).equals(Files.readSymbolicLink(Path.of("/proc/1/ns/net"))),
            "DNS fixture must be in its own network namespace"
        );
        Config original = new Config(Json.read(runtime));
        require(Commands.call(List.of(original.platform.path(PlatformConfig.Key.IP), "link", "set", "lo", "up")) == 0, "Cannot start fixture loopback");
        require(Commands.call(List.of(
            RuntimeFixture.tool("sysctl"), "-q", "-w", "net.ipv6.conf.all.disable_ipv6=0", "net.ipv6.conf.default.disable_ipv6=0"
        )) == 0, "Cannot enable fixture IPv6");
        Path shim = RuntimeFixture.script(
            work.resolve("systemd-run"),
            "exec " + RuntimeFixture.quote(Path.of(original.platform.path(PlatformConfig.Key.SYSTEMD_RUN))) +
                " --property=NetworkNamespacePath=/proc/" + ProcessHandle.current().pid() + "/ns/net \"$@\"\n"
        );
        JsonObject raw = Json.read(runtime);
        raw.addProperty("systemdRun", shim.toString());
        Path isolated = Files.writeString(work.resolve("isolated-runtime.json"), raw.toString());
        Config config = new Config(raw);
        try (
            var resolver = new HostNetwork.Listeners(List.of("127.0.0.54", "::1"), 53);
            var dot = new HostNetwork.Listeners(List.of("127.0.0.54", "::1"), 853);
            var unselected = new HostNetwork.Listeners(List.of("127.0.0.55"), 53)
        ) {
            for (String mode : List.of("internet", "lan", "local")) for (boolean explicit : List.of(false, true)) {
                var checks = new ArrayList<>(resolver.permission(!mode.equals("local") && !explicit));
                checks.addAll(dot.permission(false));
                checks.addAll(unselected.permission(false));
                var policy = Policy.read(obj(
                    "name", "run", "network", mode, "allowLocalhost", true,
                    "networkConfig", obj("localDeniedPorts", explicit ? List.of(53) : List.of(), "relayServices", List.of()),
                    "command", List.of(payload.toString(), "_payload", "ENDPOINTS", Json.encode(checks).toString())
                ));
                try (var lifetime = new Lifetime()) {
                    var prepared = Launch.prepare(policy, config, lifetime);
                    try (var frozen = Network.freeze(policy, config.platform, SystemdInstances.HOST, () -> Network.capture(
                        policy.network().mode(), () -> explicit ? List.of(Endpoint.Port.DNS) : List.of(),
                        () -> new Egress.Resolver(
                            "nameserver 127.0.0.54\nnameserver ::1\n",
                            List.of(new Endpoint.Address("127.0.0.54"), new Endpoint.Address("::1"))
                        )
                    ))) {
                        require(
                            Platform.runUnit(prepared, frozen.boundary(), config.platform, isolated) == 0,
                            "Exact DNS fixture failed: " + mode + "/explicit=" + explicit
                        );
                    }
                }
            }
        }
        System.out.println("PASS: captured resolver TCP/UDP IPv4/IPv6 exact exception, unselected DNS/DoT denial and explicit deny precedence");
    }
    
}