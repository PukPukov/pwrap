package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Synthetic public-marker services on a private session bus, no real daemon state. */
final class HostDbus {

    static final String PATH = "/org/example/Allowed", IFACE = "org.example.Interface";

    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        Path session = Files.createDirectory(work.resolve("session"));
        String address = "unix:path=" + session.resolve("bus");
        String name = "org.example.PwrapFixture.n" + UUID.randomUUID().toString().replace("-", "");
        String alias = name + ".Alias";
        Process daemon = start(work, "bus", List.of(RuntimeFixture.tool("dbus-daemon"), "--session", "--nofork", "--address=" + address), Map.of());
        try {
            await(() -> Files.exists(session.resolve("bus")), "Private session bus not ready");
            try (var bus = new FixtureBus(address); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                bus.own(name);
                bus.own(alias);
                var finished = new AtomicBoolean();
                var server = threads.submit(() -> {
                    try { serve(bus, () -> finished.get()); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                });
                try {
                    var operation = obj(
                        "destination", name, "interface", IFACE, "path", PATH, "members", List.of("Read", "Emit")
                    );
                    var broadcast = operation.deepCopy();
                    broadcast.add("members", Json.encode(List.of("Changed")));
                    var policy = obj(
                        "name", "run", "command", List.of(payload.toString(), "_payload", "DBUS", name, bus.unique(), alias),
                        "dbusCalls", List.of(operation), "dbusBroadcasts", List.of(broadcast)
                    );
                    Path manifest = write(work.resolve("profile.json"), policy.toString());
                    require(run(work, "exact", runtime.command(List.of("profile", manifest.toString())),
                    Map.of("XDG_RUNTIME_DIR", session.toString(), "DBUS_SESSION_BUS_ADDRESS", address)) == 0,
                    "Exact D-Bus calls/signals fixture failed: " + work);
                } finally {
                    finished.set(true);
                    server.get(10, TimeUnit.SECONDS);
                }
            }
            System.out.println("PASS: real exact-operation proxy calls/signals, unique-name/alias/path/interface/member restrictions; no sticky TALK");
        } finally {
            daemon.destroy();
            if (!daemon.waitFor(3, TimeUnit.SECONDS)) daemon.destroyForcibly().waitFor();
        }
        notifications(runtime, work);
    }

    private static void serve(FixtureBus bus, BooleanSupplier finished) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!finished.getAsBoolean()) {
            require(System.nanoTime() < deadline, "Unbounded D-Bus fixture");
            if (!bus.readable(100)) continue;
            var request = bus.read();
            if (request.type() != 1) continue;
            String member = request.field(3);
            bus.reply(request, member.equals("Emit") ? List.of() : List.of(member.equals("Read") ? "public-canary" : "forbidden-public-canary"));
            if (member.equals("Emit")) for (var signal : List.<List<String>>of(
                List.of(PATH, IFACE, "Changed"), List.of(PATH, IFACE, "OtherSignal"),
                List.of("/org/example/Other", IFACE, "Changed"), List.of(PATH, "org.example.Other", "Changed")
            )) bus.signal("", signal.get(0), signal.get(1), signal.get(2));
        }
    }

    private static FixtureBus.Message call(FixtureBus bus, String destination, String path, String iface, String member) throws IOException {
        return bus.call(destination, path, iface, member, "", new FixtureBus.Writer());
    }

    static void denied(FixtureBus.Message reply) {
        require(reply.type() == 3 && Set.of(
            "org.freedesktop.DBus.Error.AccessDenied", "org.freedesktop.DBus.Error.ServiceUnknown",
            "org.freedesktop.DBus.Error.NameHasNoOwner"
        ).contains(reply.field(4)), "Proxy denial contract changed: " + reply.field(4));
    }

    static void probe(List<String> arguments) throws Exception {
        require(arguments.size() == 3, "DBUS name owner alias");
        String name = arguments.getFirst(), owner = arguments.get(1), alias = arguments.get(2);
        try (var bus = new FixtureBus(required(System.getenv("DBUS_SESSION_BUS_ADDRESS"), "Missing confined bus"))) {
            for (String destination : List.of(name, owner)) {
                var reply = call(bus, destination, PATH, IFACE, "Read");
                require(reply.type() == 2 && reply.firstString().equals("public-canary"), "Permitted D-Bus Read failed");
                denied(call(bus, destination, PATH, IFACE, "Forbidden"));
                denied(call(bus, destination, "/org/example/Other", IFACE, "Read"));
                denied(call(bus, destination, PATH, "org.example.Other", "Read"));
            }
            denied(call(bus, alias, PATH, IFACE, "Read"));
            denied(call(bus, owner, PATH, IFACE, "Forbidden"));
            require(bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s",
            new FixtureBus.Writer().string("type='signal',sender='" + name + "'")).type() == 2, "Fixture AddMatch failed");
            require(call(bus, name, PATH, IFACE, "Emit").type() == 2, "Permitted Emit failed");
            Thread.sleep(300);
            bus.drain();
            var signals = bus.signals.stream()
                .filter(message -> message.field(2).startsWith("org.example."))
                .map(message -> List.of(message.field(1), message.field(2), message.field(3))).toList();
            require(signals.equals(List.of(List.of(PATH, IFACE, "Changed"))), "Signal proxy widened or dropped selected broadcast: " + signals);
            denied(call(bus, owner, PATH, IFACE, "Forbidden"));
        }
    }

    private static void notifications(Runtime runtime, Path work) throws Exception {
        String root = System.getenv("XDG_RUNTIME_DIR");
        if (root == null || !Files.exists(Path.of(root, "bus"))) {
            System.out.println("SKIP: optional standard notification metadata (no real session bus)");
            return;
        }
        var standard = obj(
            "destination", "org.freedesktop.Notifications", "interface", "org.freedesktop.Notifications",
            "path", "/org/freedesktop/Notifications",
            "members", List.of("GetCapabilities", "GetServerInformation")
        );
        var command = new ArrayList<String>(List.of(
            runtime.config().platform.path(PlatformConfig.Key.BUSCTL), "--address=unix:path=" + root + "/bus",
            "--json=short", "call", "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications", "GetServerInformation"
        ));
        var policy = obj("name", "run", "command", command, "dbusCalls", List.of(standard));
        Path manifest = write(work.resolve("notification.json"), policy.toString());
        require(run(work, "notification-metadata", runtime.command(List.of("profile", manifest.toString()))) == 0,
        "Standard notification metadata call failed");
        var response = Json.read(work.resolve("notification-metadata.log"));
        require(response.getAsJsonArray("data").size() == 4, "Unexpected notification metadata reply");
        command.set(command.size() - 2, "org.dunstproject.cmd0");
        command.set(command.size() - 1, "AuditNoSuchMember");
        policy.add("command", Json.encode(command));
        write(manifest, policy.toString());
        require(run(work, "notification-deny", runtime.command(List.of("profile", manifest.toString()))) != 0 &&
            Files.readString(work.resolve("notification-deny.log")).contains("Access denied"),
        "Custom daemon interface not denied before dispatch");
        System.out.println("PASS: standard notification metadata only; custom interface refused without reading history or changing notifications");
    }
    
}