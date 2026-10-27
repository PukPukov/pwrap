package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.backends.nixos.Network;
import ru.pukpukov.pwrap.backends.nixos.SystemdInstances;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Single-instance handoff is allowed only to the exact protected profile cgroup. */
final class Activation {

    private record Reply(@Nullable String type, List<JsonElement> data) {}

    private static JsonElement data(String output) { return decode(JsonParser.parseString(output), Reply.class).data.getFirst(); }
    private static List<String> bus(Launch.Prepared p, Config config) { return List.of(config.platform.path(BUSCTL), "--address=" + p.identity().bus()); }
    private static List<String> call(Launch.Prepared p, Config config) {
        return Commands.join(
            bus(p, config), List.of("--json=short", "call", "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")
        );
    }
    private static long pid(JsonElement value) {
        long pid = decode(value, Long.class);
        require(pid > 0, "Invalid D-Bus peer PID");
        return pid;
    }

    static void checkDomain(Launch.Prepared p, Config config) throws Exception {
        String prefix = p.policy().integration().instanceBusPrefix();
        if (prefix == null || prefix.isEmpty()) return;
        JsonArray names = data(Commands.output(Commands.join(call(p, config), List.of("ListNames")))).getAsJsonArray();
        for (JsonElement value : names) {
            String name = decode(value, String.class);
            if (!name.startsWith(prefix)) continue;
            long owner = pid(data(Commands.output(Commands.join(call(p, config), List.of("GetConnectionUnixProcessID", "s", name)))));
            require(
                SystemdInstances.HOST.processInProfile(p.policy().name(), owner),
                "Unconfined instance still owns " + name + "; close it before starting this pwrap (no unsandboxed handoff)"
            );
        }
    }

    static boolean activate(Launch.Prepared p, Network.Boundary boundary, Config config) throws Exception {
        return activate(p, boundary, config, SystemdInstances.HOST);
    }

    static boolean activate(Launch.Prepared p, Network.Boundary boundary, Config config, Instances instances) throws Exception {
        Policy.Activation activation = p.policy().integration().activation();
        List<String> extra = p.policy().invocation().activationArguments();
        if (activation == null || extra == null) return false;
        instances.register(
            p.policy().name(), null, Launch.fingerprint(p.authority(boundary, config.platform.identity)),
            p.session().values(), Map.of(), false
        );
        var result = Commands.result(Commands.join(call(p, config), List.of("GetNameOwner", "s", activation.name())), System.getenv(), true);
        if (result.code() != 0) return false;
        String unique = decode(data(result.output()), String.class);
        require(unique.matches(":[0-9]+\\.[0-9]+"), "Invalid activation peer identity");
        long owner = pid(data(Commands.output(Commands.join(call(p, config), List.of("GetConnectionUnixProcessID", "s", unique)))));
        require(instances.processInProfile(p.policy().name(), owner), "An unconfined instance is still running; close it before starting this profile");
        List<String> uris = extra.stream().filter(s -> !s.startsWith("-")).toList();
        List<String> command = Commands.join(
            bus(p, config), List.of("--auto-start=no", "call", unique, activation.path(), "org.freedesktop.Application")
        );
        if (uris.isEmpty()) command.addAll(List.of("Activate", "a{sv}", "0"));
        else {
            command.addAll(List.of("Open", "asa{sv}", "" + uris.size()));
            command.addAll(uris);
            command.add("0");
        }
        Commands.check(command);
        return true;
    }
    
}