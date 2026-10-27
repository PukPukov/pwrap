package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;

/** Actual generated org.freedesktop.Application profile: one live confined payload. */
public final class HostActivation {
    
    private record Fixture(String testRoot, String command) {}
    
    public static void check(Path document) throws Exception {
        Fixture fixture = Json.read(document, Fixture.class);
        Path root = Path.of(fixture.testRoot);
        require(
            root.toString().startsWith("/var/tmp/pwrap-activation-smoke-") &&
                !Files.exists(root, LinkOption.NOFOLLOW_LINKS),
            "Fresh activation fixture required"
        );
        Files.createDirectory(root);
        Process first = start(root, "launch", List.of(fixture.command), Map.of());
        try {
            await(() -> Files.exists(root.resolve("ready")), "Activation fixture did not become ready: " + root);
            String uri = "https://example.invalid/cohort";
            require(run(root, "open", List.of(fixture.command, uri)) == 0, "Confined URI activation failed");
            require(Json.decode(
                JsonParser.parseString(Files.readString(root.resolve("received.json"))), new TypeToken<List<String>>() {}
            ).equals(List.of(uri)), "Activation URI arguments changed");
            require(
                run(root, "activate", List.of(fixture.command)) == 0 && Files.exists(root.resolve("activated")) &&
                    Files.readAllLines(root.resolve("starts")).equals(List.of("started")) && first.isAlive(),
                "Repeated activation started a second payload"
            );
            Files.writeString(root.resolve("result.json"), "{\"open\":true,\"activate\":true,\"payloadStarts\":1}");
            System.out.println("PASS: real generated standard URI/Activate handoff, one confined payload; evidence " + root);
        } finally {
            write(root.resolve("finish"), "");
            if (!first.waitFor(20, TimeUnit.SECONDS)) {
                first.destroy();
                if (!first.waitFor(5, TimeUnit.SECONDS)) first.destroyForcibly().waitFor();
            }
        }
    }
    
    static void serve(List<String> arguments) throws Exception {
        require(arguments.size() == 2, "APPLICATION root name");
        Path root = Path.of(arguments.getFirst());
        Files.writeString(root.resolve("starts"), "started\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (var bus = new FixtureBus(required(System.getenv("DBUS_SESSION_BUS_ADDRESS"), "Missing confined activation bus"))) {
            bus.own(arguments.get(1));
            write(root.resolve("ready"), "");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!Files.exists(root.resolve("finish"))) {
                require(System.nanoTime() < deadline, "Activation fixture not released");
                if (!bus.readable(100)) continue;
                var request = bus.read();
                if (request.type() != 1) continue;
                require(
                    request.field(1).equals("/org/pwrap/Application") &&
                        request.field(2).equals("org.freedesktop.Application"),
                    "Unexpected activation target"
                );
                var body = new FixtureBus.Reader(request.body(), request.order());
                switch (request.field(3)) {
                    case "Open" -> {
                        require(request.field(8).equals("asa{sv}"), "Unexpected Open signature");
                        HostFixtures.publish(root.resolve("received.json"), Json.encode(body.strings()));
                    }
                    case "Activate" -> {
                        require(request.field(8).equals("a{sv}"), "Unexpected Activate signature");
                        write(root.resolve("activated"), "");
                    }
                    default -> throw new IllegalArgumentException("Unexpected fixture method");
                }
                body.align(4);
                require(body.data.getInt() == 0, "Expected empty activation platform_data");
                bus.reply(request, List.of());
            }
        }
    }
    
}