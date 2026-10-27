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

/** Changing owned relay listeners must not silently change a live app's cohort. */
final class HostRelayLifetime {
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        Path output = Files.createDirectory(work.resolve("output")), ready = work.resolve("ready.json"), control = work.resolve("rotate");
        int oldPort, newPort;
        try (var old = new ServerSocket(0); var next = new ServerSocket(0)) {
            oldPort = old.getLocalPort();
            newPort = next.getLocalPort();
        }
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String relay = "pwrap-fixture-relay-" + token + ".service", name = "net-life-" + token;
        String ctl = Path.of(runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN)).resolveSibling("systemctl").toString();
        Process first = null;
        String target = "";
        try {
            require(run(work, "relay-start", List.of(
                runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN), "--quiet", "--collect", "--service-type=exec",
                "--unit=" + relay, "--property=RuntimeMaxSec=180", "--", payload.toString(), "_fixture", "ROTATE",
                control.toString(), ready.toString(), Integer.toString(oldPort), Integer.toString(newPort)
            )) == 0, "Fixture relay did not start");
            await(() -> Files.exists(ready) && Json.read(ready, HostFixtures.Listening.class).port().value() == oldPort, "Relay not ready");
            var policy = obj(
                "name", name, "network", "internet", "allowLocalhost", true,
                "networkConfig", obj("localDeniedPorts", List.of(), "relayServices", List.of(relay)),
                "writable", List.of(output.toString()),
                "command", List.of(payload.toString(), "_payload", "RELAY", output.toString(), Integer.toString(oldPort), Integer.toString(newPort))
            );
            Path manifest = store(runtime, write(work.resolve("target.json"), policy.toString()));
            Runtime routes = runtime;
            first = start(work, "first", profile(routes, manifest, "--hold"), Map.of());
            await(() -> Files.exists(output.resolve("hold.json")), "First relay target did not become ready");
            observed(output, "hold", List.of(false, false));
            write(control, "");
            await(() -> Json.read(ready, HostFixtures.Listening.class).port().value() == newPort, "Relay did not rotate");
            try (var _ = new ServerSocket(oldPort, 32, InetAddress.getByName("127.0.0.1"))) {
                require(run(work, "direct-repeat", profile(routes, manifest, "audittest:repeat")) == 0, "Live target rejected changed relay discovery");
                observed(output, "repeat", List.of(false, true));
                Files.delete(output.resolve("repeat.json"));
                portal(routes, payload, manifest, work);
                observed(output, "repeat", List.of(false, true));
                Path registry = Path.of("/run/pwrap-instances", name);
                await(() -> glob(registry, "*.json").size() == 1, "Completed handoff kept extra records");
                target = unit(registry);
                first.destroy();
                require(first.waitFor(10, TimeUnit.SECONDS) && first.exitValue() == 143, "Launcher did not terminate by SIGTERM");
                require(run(work, "target-active", List.of(ctl, "is-active", "--quiet", target)) == 0, "Outer death stopped held target unexpectedly");
                Files.delete(output.resolve("repeat.json"));
                portal(routes, payload, manifest, work);
                observed(output, "repeat", List.of(false, true));
                for (boolean network : List.of(true, false)) {
                    JsonObject changed = policy.deepCopy();
                    if (network) changed.add("networkConfig", obj("localDeniedPorts", List.of(newPort), "relayServices", List.of(relay)));
                    else changed.add("writable", Json.encode(List.of()));
                    Path path = write(work.resolve("changed.json"), changed.toString());
                    String label = network ? "changed-network" : "changed-grants";
                    require(
                        run(work, label, profile(routes, path, "audittest:fresh")) != 0 &&
                            Files.readString(work.resolve(label + ".log")).contains("Close the existing") &&
                            !Files.exists(output.resolve("fresh.json")),
                        "Changed intent borrowed old relay authority"
                    );
                }
                write(output.resolve("finish"), "");
                String heldUnit = target;
                await(() -> Commands.call(List.of(ctl, "is-active", "--quiet", heldUnit)) != 0, "Held relay target did not exit");
                require(run(work, "fresh", profile(routes, manifest, "audittest:fresh")) == 0, "Fresh relay cohort did not start");
                observed(output, "fresh", List.of(true, false));
            }
            System.out.println("PASS: rotating relay/direct+URI handoff, outer death and frozen boundary; changed intent denied, fresh cohort recaptures");
        } finally {
            write(output.resolve("finish"), "");
            if (!target.isEmpty()) stop(runtime, target);
            stop(runtime, relay);
            if (first != null && !first.waitFor(10, TimeUnit.SECONDS)) first.destroyForcibly().waitFor();
        }
    }
    private static void portal(Runtime runtime, Path payload, Path manifest, Path work) throws Exception {
        Path peer = Files.createTempDirectory(work, "portal-"), session = Files.createDirectory(peer.resolve("session"));
        String address = "unix:path=" + session.resolve("bus");
        Process daemon = start(peer, "bus", List.of(RuntimeFixture.tool("dbus-daemon"), "--session", "--nofork", "--address=" + address), Map.of());
        try {
            await(() -> Files.exists(session.resolve("bus")), "Relay test's private portal bus not ready");
            var command = Commands.join(
                List.of(payload.resolveSibling("pwrap-portal-fixture").toString(), address, peer.toString()),
                runtime.command(List.of("profile", manifest.toString(), "--"))
            );
            Process service = start(peer, "portal", command, Map.of());
            try {
                await(() -> Files.exists(peer.resolve("ready")), "Relay test's standard portal not ready");
                require(run(peer, "handoff", runtime.command(List.of(
                    "run", "--open-uri", "audittest", "--",
                    runtime.config().platform.path(PlatformConfig.Key.PORTAL_TOOLS) + "/bin/xdg-open", "audittest:repeat"
                )), Map.of("XDG_RUNTIME_DIR", session.toString(), "DBUS_SESSION_BUS_ADDRESS", address)) == 0,
                    "Standard portal relay handoff failed"
                );
            } finally {
                service.destroy();
                if (!service.waitFor(3, TimeUnit.SECONDS)) service.destroyForcibly().waitFor();
            }
        } finally {
            daemon.destroy();
            if (!daemon.waitFor(3, TimeUnit.SECONDS)) daemon.destroyForcibly().waitFor();
        }
    }
    private static void observed(Path output, String mode, List<Boolean> expected) throws Exception {
        Path path = output.resolve(mode + ".json");
        await(() -> Files.exists(path), "Relay target marker missing: " + mode);
        require(
            Json.decode(JsonParser.parseString(Files.readString(path)), new TypeToken<List<Boolean>>() {}).equals(expected),
            "Relay effective boundary changed: " + mode
        );
    }
    static void probe(List<String> arguments) throws Exception {
        require(arguments.size() == 4, "RELAY output old-port new-port mode");
        Path output = Path.of(arguments.getFirst());
        String mode = switch (arguments.get(3)) {
            case "--hold" -> "hold";
            case "audittest:repeat" -> "repeat";
            case "audittest:fresh" -> "fresh";
            default -> throw new IllegalArgumentException("Unknown relay fixture mode");
        };
        var observed = new ArrayList<Boolean>();
        for (String value : arguments.subList(1, 3)) {
            observed.add(HostNetwork.connected(
                new HostNetwork.Check(
                    new Endpoint.Address("127.0.0.1"),
                    new Endpoint.Port(Integer.parseInt(value)), HostNetwork.Transport.TCP, false
                )
            ));
        }
        HostFixtures.publish(output.resolve(mode + ".json"), Json.encode(observed));
        if (mode.equals("hold")) await(() -> Files.exists(output.resolve("finish")), "Held relay fixture not released");
    }
}