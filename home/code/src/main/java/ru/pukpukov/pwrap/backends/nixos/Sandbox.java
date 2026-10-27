package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Owns namespaces, pinned file authority, seccomp descriptors and helper lifetimes. */
public final class Sandbox implements AutoCloseable {
    
    private final PlatformConfig config; private final Lifetime lifetime;
    public final Path temp; public final List<String> arguments;
    public final List<Posix.Fd> fds = new ArrayList<>();
    private FileAuthority.@MonotonicNonNull Pins pins;
    
    public Sandbox(PlatformConfig config) throws IOException { this(config, new Lifetime()); }
    
    /** Takes ownership of this workspace and every resource added to it. */
    public Sandbox(PlatformConfig config, Lifetime lifetime) throws IOException {
        this.config = config; this.lifetime = lifetime; temp = lifetime.temp("pwrap-inner-"); arguments = baseline(config);
    }
    
    public static List<String> baseline(PlatformConfig config) {
        return Commands.args(
            config.path(BWRAP), "--unshare-all", "--unshare-user", "--disable-userns", "--assert-userns-disabled",
            "--cap-drop", "ALL", "--new-session", "--die-with-parent", "--clearenv", "--dev", "/dev",
        "--proc", "/proc", "--tmpfs", "/proc/driver", "--tmpfs", "/tmp");
    }
    
    public static List<String> readOnly(Collection<String> paths) throws IOException {
        List<String> result = new ArrayList<>();
        for (String path : paths) result.addAll(List.of("--ro-bind", Fs.real(path), path));
        return result;
    }
    
    public static List<String> ipcPrefix(PlatformConfig config, Policy.Network network) {
        return !network.sharesHost() ? List.of() :
            network.hostAbstractSockets() ? List.of(config.path(SCOPE), "--allow-abstract") : List.of(config.path(SCOPE));
    }
    
    public <T extends AutoCloseable> T own(T resource) { return lifetime.use(resource); }
    
    public Posix.Fd[] pipe() throws IOException { var pair = Posix.pipe(); own(pair[0]); own(pair[1]); return pair; }
    
    public void pin(List<FileAuthority.Grant> grants, String home, List<String> exclusions) throws Exception {
        require(pins == null, "Payload file authority was already pinned");
        pins = own(new FileAuthority.Pins(grants, FileAuthority.validator(home, exclusions)));
        if (!grants.isEmpty()) FileVerifier.preflight(grants, config, pins.fds());
        arguments.addAll(Mounts.authority(pins));
    }
    
    public Posix.Child start(List<String> command, List<String> prefix) throws IOException {
        require(pins != null, "Payload file authority must be pinned before start");
        Posix.Fd seccomp = own(Posix.open(config.path(FILTER), Posix.O_RDONLY())); fds.add(seccomp);
        List<String> argv = Commands.join(arguments, List.of("--seccomp", "" + seccomp.n, "--"), prefix, command);
        List<Posix.Fd> inherited = new ArrayList<>(fds); inherited.addAll(pins.fds());
        Posix.Child app = own(Posix.spawn(argv, Map.of("LANG", "C"), inherited, 0, 1, 2));
        pins.close(); fds.forEach(Posix.Fd::close); fds.clear();
        return app;
    }
    
    public Posix.Child wayland(String source, Path output) throws Exception {
        List<String> mounts = List.of(
        "--bind", source, "/run/upstream", "--ro-bind", config.path(WAYLAND_PROXY_CONFIG), "/etc/wayland-firewall/config.toml");
        List<String> args = List.of(
            "--listen", "/out/socket", "--upstream", "/run/upstream", "--mode", "allowlist",
        "--allow", String.join(",", config.waylandInterfaces));
        return helper(mounts, output, Files.readAllLines(Path.of(config.path(WAYLAND_PROXY_CLOSURE))), config.path(WAYLAND_PROXY), args, List.of());
    }
    
    public Posix.Child dbus(Session session, Dbus policy, List<String> integrationArgs, Path output, Posix.Fd ready) throws Exception {
        List<String> mounts = List.of("--bind", session.bus().substring("unix:path=".length()), "/run/session-bus");
        List<String> args = Commands.join(
            List.of("unix:path=/run/session-bus", "/out/bus", "--filter", "--deny-proxy-socket-fds"),
            Commands.join(policy.arguments(), integrationArgs),
        List.of("--fd=" + ready.n));
        return helper(mounts, output, Files.readAllLines(Path.of(config.path(DBUS_PROXY_CLOSURE))), config.path(DBUS_PROXY), args, List.of(ready));
    }
    
    private Posix.Child helper(
        List<String> mounts, Path output, List<String> closure, String executable, List<String> arguments, List<Posix.Fd> fds
    ) throws Exception {
        try (var seccomp = Posix.open(config.path(FILTER), Posix.O_RDONLY()); var input = Posix.open("/dev/null", Posix.O_RDONLY())) {
            List<String> command = Commands.join(
                baseline(config), mounts, List.of("--bind", output.toString(), "/out"), readOnly(closure),
            List.of("--seccomp", "" + seccomp.n, "--", executable), arguments);
            List<Posix.Fd> inherited = new ArrayList<>(fds); inherited.add(seccomp);
            return own(Posix.spawn(command, System.getenv(), inherited, input.n, 1, 2));
        }
    }
    
    public static byte[] ready(Posix.Fd fd, int count) throws IOException {
        byte[] data = fd.read(count, 15000); require(data.length != 0, "Sandbox helper exited before readiness"); return data;
    }
    
    public void close() throws Exception { lifetime.close(); }
    
}