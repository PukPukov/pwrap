package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Pulse-compatible endpoint and cookie only; never native PipeWire control. */
public final class Audio {
    
    public record Connection(Map<String, String> files, Map<String, String> environment) {
        public Connection { files = Map.copyOf(files); environment = Map.copyOf(environment); }
    }
    
    private static boolean socket(Path path, boolean follow) throws IOException {
        return (((Number) Files.getAttribute(path, "unix:mode",
            follow ? new LinkOption[]{} : new LinkOption[]{LinkOption.NOFOLLOW_LINKS}
        )).intValue() & 0170000) == 0140000;
    }
    
    public static Connection discover(Map<String, String> environment) throws IOException {
        String home = environment.getOrDefault("HOME", "/root");
        String runtime = environment.getOrDefault("XDG_RUNTIME_DIR", "/run/user/" + Posix.getuid());
        String configured = environment.get("PULSE_SERVER");
        List<String> candidates = configured == null || configured.isEmpty() ?
            List.of(runtime + "/pulse/native", "/run/pulse/native") :
            List.of(configured.startsWith("unix:") ? configured.substring(5) : configured);
        String endpoint = candidates.stream().filter(p -> Files.exists(Path.of(p))).findFirst().orElse(null);
        if (endpoint == null) {
            require(configured == null || configured.isEmpty(), "Configured Pulse audio socket does not exist: " + configured);
            return new Connection(Map.of(), Map.of());
        }
        require(Path.of(endpoint).isAbsolute() && socket(Path.of(endpoint), true), "Pulse endpoint is not a local UNIX socket: " + endpoint);
        Map<String, String> files = new LinkedHashMap<>(), env = new LinkedHashMap<>();
        files.put(endpoint, Fs.real(endpoint));
        env.putAll(Map.of("PULSE_SERVER", "unix:" + endpoint, "XDG_RUNTIME_DIR", runtime));
        String cookie = Fs.absolute(environment.getOrDefault(
            "PULSE_COOKIE", environment.getOrDefault("XDG_CONFIG_HOME", home + "/.config") + "/pulse/cookie"
        ));
        if (Fs.exists(cookie)) {
            require(Files.isRegularFile(Path.of(cookie), LinkOption.NOFOLLOW_LINKS), "Pulse cookie must be a regular file: " + cookie);
            require(Fs.real(cookie).equals(cookie), "Pulse cookie path must be canonical, without symlink components: " + cookie);
            files.put(cookie, cookie);
            env.put("PULSE_COOKIE", cookie);
        }
        return new Connection(files, env);
    }
    
    private record Nixos(String socketPath) {
        Nixos {
            require(
                socketPath != null && socketPath.startsWith("/") && socketPath.indexOf(0) < 0,
                "nixos audio adapter socketPath must be an absolute path"
            );
        }
    }
    
    private record Adapters(@Nullable Map<String, Nixos> audio) {}
    
    public static Connection connect(PlatformConfig config, Policy.@Nullable Audio selected, Map<String, String> environment) throws IOException {
        require(selected != null, "audio requires an explicit provider: direct or nixos");
        Map<String, String> env = new HashMap<>(environment);
        if (selected == Policy.Audio.NIXOS) {
            JsonElement metadata = config.metadata("providers");
            require(!metadata.isJsonNull(), "nixos audio provider requires an enabled NixOS adapter");
            Adapters adapters = decode(metadata, Adapters.class);
            require(adapters.audio != null, "nixos audio provider requires an enabled NixOS adapter");
            Nixos adapter = required(adapters.audio.get("nixos"), "nixos audio provider requires an enabled NixOS adapter");
            String endpoint = adapter.socketPath;
            Path path = Path.of(endpoint);
            require(Files.exists(path) && socket(path, false), "nixos audio adapter endpoint is not a real UNIX socket: " + endpoint);
            env.remove("PULSE_COOKIE");
            env.put("PULSE_SERVER", "unix:" + endpoint);
        }
        Connection connection = discover(env);
        boolean endpoint = false;
        for (String path : connection.files.values()) if (socket(Path.of(path), true)) endpoint = true;
        require(endpoint, "Requested audio provider has no local Pulse socket");
        return connection;
    }
    
}