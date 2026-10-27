package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.tainting.qual.*;
import ru.pukpukov.pwrap.core.Endpoint.Port;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import static ru.pukpukov.pwrap.core.Json.*;

/** Public manifest, parsed once into immutable, validated authority. */
public record Policy(
    String name, List<String> command, FileAccess files, Network network,
    Integration integration, Map<String, String> environment, @Nullable Limits limits, Invocation invocation
) {
    
    private static final Set<String> RESERVED = Set.of("http", "https", "file", "javascript", "data", "about", "chrome", "resource", "blob");
    public enum Mode {
        @SerializedName("none") NONE,
        @SerializedName("internet") INTERNET,
        @SerializedName("lan") LAN,
        @SerializedName("local") LOCAL,
        @SerializedName("host") HOST
    }
    public enum Execution { @SerializedName("managed") MANAGED, @SerializedName("direct") DIRECT }
    public enum Audio { @SerializedName("direct") DIRECT, @SerializedName("nixos") NIXOS }
    public record FileAccess(
        List<String> readOnly, List<String> writable, List<String> persist,
        List<String> forbiddenWrites, List<String> immutableData, @Nullable String downloads
    ) {
        public FileAccess {
            readOnly = nonempty(readOnly); writable = nonempty(writable); persist = nonempty(persist);
            forbiddenWrites = nonempty(forbiddenWrites); immutableData = nonempty(immutableData);
            if (downloads != null) {
                require(
                    (downloads.startsWith("/") || downloads.startsWith("~/"))
                        && downloads.indexOf(0) < 0 && downloads.indexOf('\n') < 0 && downloads.indexOf('\r') < 0,
                    "Downloads requires an absolute or ~/ directory path on one line"
                );
                persist = Stream.concat(persist.stream(), Stream.of(downloads)).distinct().toList();
            }
        }
    }
    public record NetworkSettings(List<Port> localDeniedPorts, List<String> relayServices) {
        public NetworkSettings {
            require(
                relayServices.stream().allMatch(s -> s.matches("[A-Za-z0-9_@.-]+\\.service") && !s.contains("..")),
                "relayServices must name systemd service units"
            );
            localDeniedPorts = List.copyOf(localDeniedPorts); relayServices = List.copyOf(relayServices);
        }
        @Json.Input private static final class Input {
            List<Port> localDeniedPorts = List.of(); List<String> relayServices = List.of();
        }
        public static NetworkSettings read(JsonElement value) {
            Input input = decode(value, Input.class); return new NetworkSettings(input.localDeniedPorts, input.relayServices);
        }
    }
    
    public record Routes(Mode mode, boolean allowLocalhost, boolean allowLAN) {}
    public record Network(Mode mode, boolean allowLocalhost, boolean allowLAN, boolean hostAbstractSockets, @Nullable NetworkSettings settings) {
        public Network {
            require(mode != null, "Missing network mode");
            require(!allowLocalhost || Set.of(Mode.INTERNET, Mode.LAN, Mode.LOCAL).contains(mode), "allowLocalhost requires internet, lan or local");
            require(!allowLAN || mode == Mode.LAN || mode == Mode.LOCAL, "allowLAN requires lan or local networking");
            require(mode != Mode.LOCAL || allowLAN || allowLocalhost, "local mode requires localhost or LAN access");
            require(
                !hostAbstractSockets || mode == Mode.HOST || mode == Mode.LAN || mode == Mode.LOCAL || allowLocalhost,
                "ipc.hostAbstractSockets requires shared host network namespace"
            );
        }
        public Routes routes() { return new Routes(mode, allowLocalhost, allowLAN); }
        public boolean sharesHost() { return mode == Mode.HOST || mode == Mode.LAN || mode == Mode.LOCAL || allowLocalhost; }
    }
    public record Activation(String name, String path) {
        public Activation { require(name != null && !name.isEmpty() && path != null && !path.isEmpty(), "dbusActivation requires name and path"); }
    }
    public record Integration(
        boolean desktop, boolean gpu, boolean audio, OpenActions opens,
        boolean sharedTmp, @Nullable Audio audioProvider,
        Dbus dbus, @Nullable Activation activation, @Nullable String instanceBusPrefix
    ) {
        public Integration {
            require(!audio || audioProvider != null, "audio=true requires providers.audio");
        }
        public boolean bus() { return opens.enabled() || dbus.enabled(); }
    }
    public record Limits(String memoryMax, long tasksMax) {
        public Limits {
            require(memoryMax.matches("[1-9][0-9]*[KMGTPE]?"), "memoryMax must be a positive size such as 4G");
            require(tasksMax > 0, "tasksMax must be a positive integer");
        }
    }
    public record Invocation(
        Execution execution, @Nullable String home, List<String> inheritEnvironment,
        @Nullable String closure, @Nullable String path, @Nullable String cwd,
        @Nullable String manifest, List<String> runtime, @Nullable List<String> activationArguments
    ) {
        public Invocation {
            inheritEnvironment = List.copyOf(inheritEnvironment); runtime = List.copyOf(runtime);
            activationArguments = activationArguments == null ? null : List.copyOf(activationArguments);
            require(inheritEnvironment.stream().allMatch(k -> k.matches("[A-Za-z_][A-Za-z0-9_]*")), "Invalid inherited environment key");
        }
    }
    // Record's implicit super() has no state/inputs; CF does not model that constructor.
    @SuppressWarnings("tainting:super.invocation")
    public @Untainted Policy {
        require(name.matches("[A-Za-z0-9_-]+"), "Invalid profile name");
        require(
            !command.isEmpty() && !command.getFirst().isEmpty() && command.stream().noneMatch(s -> s.indexOf(0) >= 0),
            "command must be a nonempty string list"
        );
        command = List.copyOf(command);
        require(
            environment.keySet().stream().allMatch(k -> k.matches("[A-Za-z_][A-Za-z0-9_]*")),
            "environment must map variable names to strings"
        );
        environment = Map.copyOf(environment);
        localOnly(network.mode, integration.bus());
        if (invocation.execution == Execution.DIRECT) {
            String home = invocation.home;
            require(
                home != null && home.startsWith("/") && Path.of(home).normalize().toString().equals(home),
                "Direct execution requires a canonical absolute home"
            );
            require(
                network.mode == Mode.NONE && !network.allowLocalhost && !network.allowLAN && network.settings == null
                    && !integration.desktop && !integration.gpu && !integration.audio && !integration.opens.enabled() && !integration.sharedTmp
                    && integration.activation == null && integration.instanceBusPrefix == null && !integration.dbus.enabled() && limits == null,
                "Direct execution is file-only: no network, brokers or cgroup limits"
            );
        } else require(
            invocation.home == null && invocation.inheritEnvironment.isEmpty(),
            "Explicit home/inheritEnvironment require direct execution"
        );
        require(!integration.sharedTmp || invocation.manifest != null && !name.equals("run"), "sharedTmp requires a named profile manifest");
    }
    public record Authority(
        String name, FileAccess files, Network network, Integration integration,
        Map<String, String> environment, @Nullable Limits limits, Invocation invocation
    ) {}
    public Authority authority() {
        Invocation i = invocation;
        return new Authority(
            name, files, network, integration, environment, limits,
            new Invocation(i.execution, i.home, i.inheritEnvironment, i.closure, i.path, i.cwd, i.manifest, i.runtime, null)
        );
    }
    public @Untainted Policy withCommand(List<String> values) {
        return new Policy(name, values, files, network, integration, environment, limits, invocation);
    }
    public @Untainted Policy withFiles(FileAccess values) {
        return new Policy(name, command, values, network, integration, environment, limits, invocation);
    }
    public static boolean customScheme(String value) { return value.matches("[a-z][a-z0-9+.-]*") && !RESERVED.contains(value); }
    private static void localOnly(Mode mode, boolean brokers) {
        require(mode != Mode.LOCAL || !brokers, "local-only mode forbids host URL and D-Bus brokers");
    }
    static List<String> nonempty(List<String> values) {
        require(values != null && values.stream().allMatch(v -> v != null && !v.isEmpty() && v.indexOf(0) < 0), "Expected nonempty strings");
        return List.copyOf(values);
    }
    @Json.Input private static final class Providers {
        @Json.AllowNull @Nullable Audio audio;
        OpenActions.FileInput openFile = new OpenActions.FileInput();
        OpenActions.UriInput uri = new OpenActions.UriInput();
        OpenActions opens() {
            return new OpenActions(
                new OpenActions.OpenFile(openFile.enable, openFile.ask),
                new OpenActions.Uri(uri.enable, uri.ask, uri.schemes)
            );
        }
    }
    @Json.Input private static final class Ipc { Boolean hostAbstractSockets = false; }
    /** Only the wire schema is flat. Defaults apply to missing fields, never explicit nulls. */
    @Json.Input static final class Manifest {
        Integer schemaVersion = 1;
        String name = "run";
        List<String> command = List.of(), readOnly = List.of(), writable = List.of(), persist = List.of(),
            runtime = List.of(), forbiddenWrites = List.of(), immutableData = List.of(),
            dbusTalk = List.of(), dbusOwn = List.of(), inheritEnvironment = List.of();
        @Nullable List<String> activationArguments;
        List<Dbus.Operation> dbusCalls = List.of(), dbusBroadcasts = List.of();
        Mode network = Mode.NONE;
        NetworkSettings.@Nullable Input networkConfig;
        @Nullable Boolean gpu;
        Boolean allowLocalhost = false, allowLAN = false, desktop = false, audio = false, sharedTmp = false;
        Ipc ipc = new Ipc();
        Providers providers = new Providers();
        Map<String, String> environment = Map.of();
        @Nullable String memoryMax;
        @Json.AllowNull @Nullable String instanceBusPrefix, home, closure, path, cwd, manifest;
        @Json.AllowNull @Nullable String downloads;
        @Nullable Long tasksMax;
        @Json.AllowNull @Nullable Activation dbusActivation;
        Execution execution = Execution.MANAGED;
        Dbus dbus() { return new Dbus(nonempty(dbusTalk), nonempty(dbusOwn), dbusCalls, dbusBroadcasts); }
        @Untainted Policy policy() {
            require(schemaVersion == 1, "Unsupported policy schemaVersion (expected 1)");
            Dbus bus = dbus(); localOnly(network, providers.opens().enabled() || bus.enabled());
            NetworkSettings settings = networkConfig == null ? null : new NetworkSettings(networkConfig.localDeniedPorts, networkConfig.relayServices);
            Network net = new Network(network, allowLocalhost, allowLAN, ipc.hostAbstractSockets, settings);
            Integration integration = new Integration(
                desktop, gpu == null ? desktop : gpu, audio, providers.opens(), sharedTmp, providers.audio,
                bus, dbusActivation, instanceBusPrefix
            );
            Limits limits = execution == Execution.DIRECT && memoryMax == null && tasksMax == null ? null
                : new Limits(memoryMax == null ? "4G" : memoryMax, tasksMax == null ? 512 : tasksMax);
            return new Policy(
                name, command, new FileAccess(readOnly, writable, persist, forbiddenWrites, immutableData, downloads),
                net, integration, environment, limits,
                new Invocation(execution, home, nonempty(inheritEnvironment), closure, path, cwd, manifest, nonempty(runtime), activationArguments)
            );
        }
    }
    public static @Untainted Policy read(@Tainted JsonObject document) { return decode(document, Manifest.class).policy(); }
    
}