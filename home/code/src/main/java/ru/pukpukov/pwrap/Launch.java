package ru.pukpukov.pwrap;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.tainting.qual.*;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.*;
import ru.pukpukov.pwrap.backends.nixos.*;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;

/** Composition root: discovery chooses resources, core enforces their explicit authority. */
public final class Launch {
    
    public static final List<String> SAFE_ENV = List.of(
        "LANG", "LC_ALL", "LC_CTYPE", "TERM", "COLORTERM", "TZ"
    );
    
    public static final List<String> GUI_ENV = List.of(
        "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XCURSOR_SIZE", "XCURSOR_THEME"
    );
    
    public static final List<String> SESSION_ENV = Commands.join(
        SAFE_ENV, GUI_ENV, Desktop.DISCOVERY_ENV, List.of("PULSE_SERVER", "PULSE_COOKIE", "HOME")
    );
    
    public static final Instances.Codec<Request, Capture> CODEC = new Instances.Codec<>("launch-v2", Request.class, Capture.class);
    
    public record Request(
        Policy.Authority policy, String executable, String configuration, @Nullable String desktopMetadata, @Nullable String providerMetadata,
        Map<String, String> session
    ) {
        public Request { session = Map.copyOf(session); }
    }
    
    public record Capture(Session.Environment session, Desktop.Appearance desktop, Graphics.@Nullable Snapshot graphics) {}
    
    public record Prepared(
        Policy policy, Session.Environment session, Desktop.Appearance desktop, Graphics.@Nullable Snapshot graphics,
        Audio.Connection audio, List<Grant> grants, Storage.Runtime runtime, Map<String, String> environment,
        String cwd, @Nullable String scratch,
        Instances.@Nullable Snapshot<Request, Capture> launchSnapshot
    ) {
        
        public Prepared {
            grants = List.copyOf(grants);
            environment = Map.copyOf(environment);
            require(SESSION_ENV.containsAll(session.values().keySet()), "Invalid pwrap instance session environment");
        }
        
        public Session identity() { return session.session(); }
        
        public Authority authority(Network.Boundary boundary, String configuration) {
            return new Authority(
                policy.authority(), policy.command().getFirst(), configuration,
                session, desktop, graphics, audio, grants, runtime, environment, cwd, scratch, launchSnapshot, boundary
            );
        }
        
    }
    
    public record Authority(
        Policy.Authority policy, String executable, String configuration, Session.Environment session,
        Desktop.Appearance desktop, Graphics.@Nullable Snapshot graphics, Audio.Connection audio, List<Grant> grants,
        Storage.Runtime runtime, Map<String, String> environment, String cwd, @Nullable String scratch,
        Instances.@Nullable Snapshot<Request, Capture> launchSnapshot,
        Network.Boundary boundary
    ) {}
    
    public static String fingerprint(Authority authority) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical(encode(authority)).toString().getBytes(StandardCharsets.UTF_8))
        );
    }
    
    private static Map<String, String> selected(Map<String, String> environment, Collection<String> names) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (String key : names) if (environment.containsKey(key)) selected.put(key, environment.get(key));
        return selected;
    }
    
    private static Capture capture(Policy policy, Config config, Session.Environment session, Storage.Provenance provenance) throws Exception {
        return new Capture(
            session, policy.integration().desktop() ?
            Desktop.discover(config.platform, session, provenance) :
            Desktop.Appearance.empty(), policy.integration().gpu() ?
            Graphics.discover() :
            null
        );
    }
    
    public static Prepared prepare(@Untainted Policy input, Config config, Lifetime lifetime) throws Exception {
        return prepare(input, config, lifetime, storage(config));
    }
    
    private static Storage storage(Config config) {
        require(config.storageBackend.equals("nix"), "Unsupported storage backend: " + config.storageBackend);
        return new NixStorage(config.nix());
    }
    
    public static Prepared prepare(@Untainted Policy input, Config config, Lifetime lifetime, Storage storage) throws Exception {
        Session.Environment environment = input.invocation().execution() == Policy.Execution.DIRECT ?
            new Session.Environment(Map.of("HOME", required(input.invocation().home(), "Direct execution requires an explicit home"))) :
            Session.capture(SESSION_ENV);
        return prepare(input, config, lifetime, environment, SystemdInstances.HOST, (p, c, s) -> capture(p, c, s, storage.provenance()), storage);
    }
    
    @FunctionalInterface interface Discovery {
        Capture capture(Policy policy, Config config, Session.Environment session) throws Exception;
    }
    
    static Prepared prepare(
        @Untainted Policy input, Config config, Lifetime lifetime,
        Session.Environment environment, Instances instances, Discovery discovery
    ) throws Exception { return prepare(input, config, lifetime, environment, instances, discovery, storage(config)); }
    
    static Prepared prepare(
        @Untainted Policy input, Config config, Lifetime lifetime, Session.Environment environment,
        Instances instances, Discovery discovery, Storage storage
    ) throws Exception {
        List<String> command = new ArrayList<>(input.command());
        command.set(0, Commands.executable(command.getFirst()));
        Policy policy = input.withCommand(command);
        boolean direct = policy.invocation().execution() == Policy.Execution.DIRECT;
        require(!direct || environment.values().equals(Map.of(
            "HOME", required(policy.invocation().home(), "Direct execution requires an explicit home")
        )), "Direct execution must use its explicit home only");
        Capture discovered; String scratch = null; Instances.Snapshot<Request, Capture> snapshot = null;
        if (!direct && !policy.name().equals("run")) {
            Request request = new Request(
                policy.authority(), command.getFirst(), config.platform.identity,
                policy.integration().desktop() ? canonical(config.platform.metadata("desktop")).toString() : null,
                policy.integration().audio() ? canonical(config.platform.metadata("providers")).toString() : null,
                selected(environment.values(), List.of("HOME", "XDG_RUNTIME_DIR", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS"))
            );
            Session.Environment session = environment; Policy requested = policy;
            var lease = lifetime.use(instances.lease(
                policy.name(), CODEC, request, () -> discovery.capture(requested, config, session), policy.integration().sharedTmp()
            ));
            snapshot = lease.snapshot; discovered = snapshot.state(); scratch = snapshot.scratch();
        } else discovered = discovery.capture(policy, config, environment);
        environment = discovered.session; String home = environment.session().home();
        Desktop.Appearance desktop = discovered.desktop;
        Map<String, String> env = new LinkedHashMap<>(desktop.environment());
        env.putAll(policy.environment());
        Map<String, String> inherited = direct ? new HashMap<>(Map.of("LANG", "C")) : selected(environment.values(), SAFE_ENV);
        if (direct) for (String key : policy.invocation().inheritEnvironment()) {
            String value = System.getenv(key);
            if (value != null) inherited.put(key, value);
        }
        inherited.putAll(env);
        env = inherited;
        if (policy.integration().desktop()) {
            Map<String, String> gui = selected(environment.values(), GUI_ENV); gui.putAll(env); env = gui;
            env.putAll(Map.of(
                "GSETTINGS_BACKEND", "keyfile",
                "GDK_BACKEND", "wayland",
                "QT_QPA_PLATFORM", "wayland",
                "MOZ_ENABLE_WAYLAND", "1",
                "NIXOS_OZONE_WL", "1"
            ));
        }
        Audio.Connection audio = new Audio.Connection(Map.of(), Map.of());
        if (policy.integration().audio()) {
            Map<String, String> session = new HashMap<>(environment.values());
            session.putAll(env);
            audio = Audio.connect(config.platform, policy.integration().audioProvider(), session);
            env.remove("PULSE_SERVER"); env.remove("PULSE_COOKIE"); env.putAll(audio.environment());
        }
        List<String> immutable = new ArrayList<>();
        for (String path : policy.files().immutableData()) {
            String resolved = Fs.resolve(path, home);
            FileAuthority.validate(resolved, home);
            immutable.add(resolved);
        }
        Policy.FileAccess original = policy.files();
        policy = policy.withFiles(new Policy.FileAccess(
            Commands.join(original.readOnly(), desktop.readOnly()),
            original.writable(), original.persist(), original.forbiddenWrites(), immutable, original.downloads()
        ));
        List<Grant> grants = FileAuthority.prepare(policy.files(), home, FileVerifier.mkdir(config.platform));
        if (!audio.files().isEmpty()) FileVerifier.preflight(audio.files().entrySet().stream().map(e -> {
            return new Grant(e.getValue(), e.getKey(), false);
        }).toList(), config.platform, List.of());
        List<String> runtimeRoots = new ArrayList<>(desktop.runtimeRoots());
        List<Resources.Binding> runtimeFiles = new ArrayList<>();
        if (discovered.graphics != null) {
            runtimeRoots.addAll(discovered.graphics.runtimeRoots());
            runtimeFiles.addAll(discovered.graphics.runtimeFiles());
        }
        Storage.Runtime runtime = storage.resolve(policy, grants, new Storage.Inputs(runtimeRoots, runtimeFiles));
        String cwd = policy.invocation().cwd() == null ?
            (direct ? "/" : home) :
            policy.invocation().cwd();
        return new Prepared(policy, environment, desktop, discovered.graphics, audio, grants, runtime, env, cwd, scratch, snapshot);
    }
    
}