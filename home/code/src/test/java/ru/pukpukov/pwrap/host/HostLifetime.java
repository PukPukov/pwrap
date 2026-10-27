package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.JsonObject;
import ru.pukpukov.pwrap.Config;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Fresh units only: two windows, snapshot authority and PID1-owned cleanup. */
final class HostLifetime {
    
    @FunctionalInterface interface Condition { boolean ready() throws Exception; }
    
    static void await(Condition condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.ready()) {
            require(System.nanoTime() < deadline, description);
            Thread.sleep(50);
        }
    }
    static List<Path> glob(Path directory, String pattern) throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var stream = Files.newDirectoryStream(directory, pattern)) {
            var found = new ArrayList<Path>();
            stream.forEach(found::add);
            return found;
        }
    }
    
    @Json.Extensible private record Unit(String unit) {}
    
    static String unit(Path registry) throws IOException {
        var records = glob(registry, "*.json");
        require(records.size() == 1, "Expected one live fixture record: " + registry);
        return Json.read(records.getFirst(), Unit.class).unit;
    }
    
    static Runtime descriptor(Path path, JsonObject raw) throws IOException {
        return new Runtime(Files.writeString(path, raw.toString()), new Config(raw));
    }
    
    static List<String> profile(Runtime runtime, Path manifest, String mode) {
        return runtime.command(List.of("profile", manifest.toString(), "--", mode));
    }
    
    static void stop(Runtime runtime, String unit) throws Exception {
        Commands.call(List.of(
            Path.of(runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN)).resolveSibling("systemctl").toString(), "stop", unit
        ));
    }
    
    static void finish(Process child) throws Exception {
        require(child.waitFor(25, TimeUnit.SECONDS) && child.exitValue() == 0, "Fixture did not finish normally");
    }
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        for (boolean kill : List.of(false, true)) cohort(runtime, payload, Files.createDirectory(work.resolve(kill ? "killed" : "normal")), kill);
        authority(runtime, payload, Files.createDirectory(work.resolve("authority")));
        firewall(runtime, payload, Files.createDirectory(work.resolve("firewall")));
        System.out.println("PASS: real two-window cohorts, runtime/session authority, killed launchers and service-owned cleanup");
    }
    private static void cohort(Runtime runtime, Path payload, Path work, boolean killed) throws Exception {
        String name = "cohort-smoke-" + UUID.randomUUID().toString().replace("-", "");
        Path output = Files.createDirectory(work.resolve("output")), registry = Path.of("/run/pwrap-instances", name);
        Path manifest = write(work.resolve("profile.json"), obj(
            "name", name, "sharedTmp", true, "writable", List.of(output.toString()),
            "command", List.of(payload.toString(), "_payload", "COHORT", output.toString())
        ).toString());
        Process first = start(work, "first", profile(runtime, manifest, "first"), Map.of());
        String unit = "";
        try {
            await(() -> Files.exists(output.resolve("ready")), "First cohort did not become ready: " + work);
            unit = unit(registry);
            var scratch = glob(registry, ".scratch-*");
            require(
                scratch.size() == 1 && Files.readString(scratch.getFirst().resolve("shared-state")).equals("first cohort"),
                "Missing shared scratch state"
            );
            if (killed) {
                first.destroyForcibly();
                require(first.waitFor(10, TimeUnit.SECONDS), "Killed launcher did not exit");
            }
            require(
                run(work, "second", profile(runtime, manifest, "second")) == 0 &&
                    Files.exists(output.resolve("second")) && Files.exists(scratch.getFirst()),
                "Second window did not reuse live scratch"
            );
            if (killed) require(Commands.call(List.of(
                Path.of(runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN)).resolveSibling("systemctl").toString(),
                "kill", "--kill-whom=main", "--signal=SIGKILL", unit
            )) == 0, "Cannot kill fixture main process");
            else {
                write(output.resolve("finish"), "");
                finish(first);
            }
            await(() -> !Files.exists(scratch.getFirst()), "Scratch survived last service exit");
            await(() -> glob(registry, "*.json").isEmpty() && glob(registry, "*.lease").isEmpty(), "Cohort records/leases survived teardown");
        } finally {
            write(output.resolve("finish"), "");
            if (!unit.isEmpty()) stop(runtime, unit);
            if (first.isAlive()) {
                first.destroyForcibly();
                first.waitFor();
            }
        }
    }
    
    private static void rejected(
        Runtime runtime, Path work, Path manifest, String label, String message, Map<String, String> environment
    ) throws Exception {
        require(
            run(work, label, profile(runtime, manifest, label), environment) != 0 &&
                Files.readString(work.resolve(label + ".log")).contains(message),
            "Changed authority accepted or rejected for unrelated reason: " + label
        );
        require(!Files.exists(work.resolve("output").resolve(label)), "Rejected authority started a payload");
    }
    
    private static void authority(Runtime runtime, Path payload, Path work) throws Exception {
        String name = "authority-" + UUID.randomUUID().toString().replace("-", "");
        Path output = Files.createDirectory(work.resolve("output"));
        var raw = obj(
            "name", name, "writable", List.of(output.toString()),
            "command", List.of(payload.toString(), "_payload", "SESSION", output.toString())
        );
        Path manifest = write(work.resolve("profile.json"), raw.toString());
        JsonObject alternative = runtime.descriptor();
        alternative.add("metadata", obj("profiles", obj("unrelated", "/unused")));
        Runtime equivalent = descriptor(work.resolve("equivalent-runtime.json"), alternative);
        Process first = start(work, "first", profile(runtime, manifest, "first"), Map.of("TERM", "first-session"));
        try {
            await(() -> Files.exists(output.resolve("first")), "Authority fixture did not become ready: " + work);
            require(
                run(work, "second", profile(equivalent, manifest, "second"), Map.of("TERM", "other-context")) == 0 &&
                    Files.readString(output.resolve("second")).equals("first-session"),
                "Metadata registry changed effective launch authority"
            );
            alternative.addProperty("scope", "/different-helper");
            Runtime incompatible = descriptor(work.resolve("incompatible-runtime.json"), alternative);
            rejected(incompatible, work, manifest, "changed-helper", "Close the existing", Map.of());
            raw.add("environment", obj("EXPLICIT_AUTHORITY_CHANGE", "yes"));
            write(manifest, raw.toString());
            rejected(equivalent, work, manifest, "changed-environment", "Close the existing", Map.of());
            raw.remove("environment");
            write(manifest, raw.toString());
            rejected(equivalent, work, manifest, "changed-session", "snapshot request", Map.of("HOME", work.resolve("different-session").toString()));
        } finally {
            write(output.resolve("finish"), "");
            if (first.isAlive()) {
                try { finish(first); }
                finally { if (first.isAlive()) first.destroyForcibly().waitFor(); }
            }
        }
    }
    
    private static void firewall(Runtime runtime, Path payload, Path work) throws Exception {
        String name = "network-smoke-" + UUID.randomUUID().toString().replace("-", "");
        Path output = Files.createDirectory(work.resolve("output")), registry = Path.of("/run/pwrap-instances", name);
        try (var listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))) {
            var policy = obj(
                "name", name, "network", "lan", "writable", List.of(output.toString()),
                "networkConfig", obj("localDeniedPorts", List.of(), "relayServices", List.of()),
                "command", List.of(payload.toString(), "_payload", "WATCH", output.toString(), Integer.toString(listener.getLocalPort()))
            );
            Path manifest = write(work.resolve("profile.json"), policy.toString());
            Process first = start(work, "first", runtime.command(List.of("profile", manifest.toString())), Map.of());
            String unit = "";
            try {
                await(() -> Files.exists(output.resolve("heartbeat")), "Firewall fixture not ready: " + work);
                unit = unit(registry);
                policy.add("readOnly", Json.encode(List.of(write(work.resolve("selected"), "selected").toString())));
                write(manifest, policy.toString());
                rejected(runtime, work, manifest, "changed-grant", "Close the existing", Map.of());
                policy.remove("readOnly");
                policy.addProperty("allowLocalhost", true);
                write(manifest, policy.toString());
                rejected(runtime, work, manifest, "changed-loopback", "Close the existing", Map.of());
                String ctl = Path.of(runtime.config().platform.path(PlatformConfig.Key.SYSTEMD_RUN)).resolveSibling("systemctl").toString();
                String properties = Commands.output(List.of(ctl, "show", unit, "-p", "ExecStopPost", "-p", "IPAddressAllow", "-p", "IPAddressDeny"));
                write(work.resolve("properties.log"), properties);
                require(
                    properties.lines().filter(s -> s.startsWith("IPAddressAllow=") || s.startsWith("IPAddressDeny="))
                        .allMatch(s -> s.endsWith("=")),
                    "Unexpected divergent systemd IP policy"
                );
                var match = Pattern.compile("pwrap_[0-9a-f]{32}").matcher(properties);
                require(match.find(), "Missing fixture cleanup table");
                String table = required(match.group(), "No table identity");
                var nft = List.of(runtime.config().platform.path(PlatformConfig.Key.NFT), "list", "table", "inet", table);
                require(run(work, "table-before", nft) == 0, "Live fixture lacks its table");
                String previous = Files.readString(output.resolve("heartbeat"));
                first.destroyForcibly();
                require(first.waitFor(10, TimeUnit.SECONDS), "Killed network launcher did not exit");
                Thread.sleep(400);
                require(run(work, "table-detached", nft) == 0, "Launcher death removed live firewall");
                await(() -> !Files.readString(output.resolve("heartbeat")).equals(previous), "Detached payload stopped making progress");
                require(!Files.exists(output.resolve("unexpected")), "Detached payload lost its boundary");
                write(output.resolve("finish"), "");
                String target = unit;
                await(() -> Commands.call(List.of(ctl, "is-active", "--quiet", target)) != 0, "Fixture unit did not exit");
                await(() -> run(work, "table-after", nft) != 0, "Fixture firewall leaked after service teardown");
            } finally {
                write(output.resolve("finish"), "");
                if (!unit.isEmpty()) stop(runtime, unit);
                if (first.isAlive()) first.destroyForcibly().waitFor();
            }
        }
    }
}