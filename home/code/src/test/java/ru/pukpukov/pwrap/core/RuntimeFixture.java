package ru.pukpukov.pwrap.core;

import module java.base;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import com.google.gson.JsonObject;
import ru.pukpukov.pwrap.Config;
import ru.pukpukov.pwrap.Launch;
import ru.pukpukov.pwrap.backends.nixos.*;

/** Complete generated descriptor; test-owned paths, no dependency on the installed host. */
public final class RuntimeFixture {
    
    // Keep argv[0] names for Nix's multi-call coreutils; resolving the final link loses them.
    public static String tool(String name) throws IOException {
        Path resolved = Path.of(Commands.executable(name));
        Path named = resolved.resolveSibling(name);
        return Files.isExecutable(named) ? named.toString() : resolved.toString();
    }
    
    public static Path script(Path path, String body) throws IOException {
        Files.createDirectories(Fs.parent(path));
        Files.writeString(path, "#!" + Commands.executable("sh") + "\n" + body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
    
    public static String quote(Path path) { return "'" + path.toString().replace("'", "'\"'\"'") + "'"; }
    
    public static Launch.Prepared prepared(Policy policy, Session.Environment session) {
        return new Launch.Prepared(
            policy, session, Desktop.Appearance.empty(), null, new Audio.Connection(Map.of(), Map.of()), List.of(),
            new Storage.Runtime(List.of(), List.of(), List.of(), List.of(), "", List.of()),
            Map.of(), session.session().home(), null, null
        );
    }
    
    public static Config config(Path hostConfig, JsonObject metadata) {
        return new Config(descriptor(hostConfig, metadata));
    }
    
    public static PlatformConfig platform(Path hostConfig, JsonObject metadata) { return config(hostConfig, metadata).platform; }
    
    public static JsonObject descriptor(Path hostConfig, JsonObject metadata) {
        JsonObject raw = obj(
            "storageBackend", "nix", "platformBackend", "nixos", "entry", "/entry",
            "hostConfigDir", hostConfig.toString(), "metadata", metadata, "waylandInterfaces", List.of()
        );
        for (PlatformConfig.Key key : PlatformConfig.Key.values()) raw.addProperty(key.json, "/tools/" + key.json);
        raw.addProperty("nixStore", "/tools/nixStore");
        return raw;
    }
    
}