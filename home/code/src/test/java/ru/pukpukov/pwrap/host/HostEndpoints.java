package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Real static + cgroup-discovered relay exclusions for all supported families/protocols. */
final class HostEndpoints {
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        String relay = "pwrap-fixture-endpoints-" + UUID.randomUUID().toString().substring(0, 12) + ".service";
        Path ready = work.resolve("ready.json");
        try (var allowed = new HostNetwork.Listeners(); var excluded = new HostNetwork.Listeners()) {
            try {
                require(run(work, "relay-start", List.of(
                    runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN), "--quiet", "--collect",
                    "--service-type=exec", "--unit=" + relay, "--property=RuntimeMaxSec=180", "--",
                    payload.toString(), "_fixture", "LISTEN", ready.toString(), "127.0.0.1", "::1"
                )) == 0, "Endpoint relay fixture did not start");
                await(() -> Files.exists(ready), "Endpoint relay not ready");
                var relays = Json.read(ready, HostFixtures.Endpoints.class).endpoints();
                for (String mode : List.of("local", "internet", "lan")) for (boolean loopback : List.of(false, true)) {
                    var checks = new ArrayList<>(allowed.permission(loopback));
                    checks.addAll(excluded.permission(false));
                    checks.addAll(relays);
                    var policy = obj(
                        "name", "run", "network", mode, "allowLocalhost", loopback, "allowLAN", !mode.equals("internet"),
                        "networkConfig", obj(
                            "localDeniedPorts", excluded.endpoints.stream().map(e -> e.port().value()).distinct().toList(), "relayServices", List.of(relay)
                        ),
                        "command", List.of(payload.toString(), "_payload", "ENDPOINTS", Json.encode(checks).toString())
                    );
                    String label = mode + "-" + loopback;
                    Path manifest = write(work.resolve(label + ".json"), policy.toString());
                    require(run(work, label, runtime.command(List.of("profile", manifest.toString()))) == 0,
                        "Endpoint relay matrix failed: " + label + " (" + work + ")"
                    );
                }
            } finally { stop(runtime, relay); }
        }
        Path dns = Files.createDirectory(work.resolve("dns"));
        // Enable IPv6 before exec: java.net caches protocol availability when
        // the process starts, and a new namespace initially has no active lo.
        var dnsCommand = List.of(
            RuntimeFixture.tool("unshare"), "--net", "--", runtime.config().platform.path(PlatformConfig.Key.SHELL), "-ec",
            "\"$1\" link set lo up; \"$2\" -q -w net.ipv6.conf.all.disable_ipv6=0 " +
                "net.ipv6.conf.default.disable_ipv6=0; exec \"$3\" _fixture DNS \"$4\" \"$3\" \"$5\"",
            "fixture", runtime.config().platform.path(PlatformConfig.Key.IP), RuntimeFixture.tool("sysctl"), payload.toString(),
            runtime.file().toString(), dns.toString()
        );
        require(run(work, "dns", dnsCommand) == 0, "Captured DNS namespace fixture failed: " + work);
        if ("1".equals(System.getenv("PWRAP_TEST_HTTPS"))) for (String mode : List.of("internet", "lan")) {
            var policy = obj(
                "network", mode, "allowLocalhost", true, "networkConfig", obj("localDeniedPorts", List.of(443), "relayServices", List.of()),
                "command", List.of(
                    runtime.config().platform.path(PlatformConfig.Key.TOOLBOX) + "/bin/curl", "--noproxy", "*",
                    "--fail", "--silent", "--max-time", "25", "--output", "/dev/null", "--write-out", "%{http_code}", "https://example.com"
                )
            );
            Path manifest = write(work.resolve("https-" + mode + ".json"), policy.toString());
            require(run(work, "https-" + mode, runtime.command(List.of("profile", manifest.toString()))) == 0 &&
                Files.readString(work.resolve("https-" + mode + ".log")).strip().endsWith("200"),
                "Local port exclusion blocked optional remote HTTPS fixture"
            );
        }
        System.out.println("PASS: real static/relay TCP+UDP IPv4+IPv6 exclusions and exact captured DNS fixture");
    }
    
}