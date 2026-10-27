package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.annotations.SerializedName;
import org.checkerframework.checker.nullness.qual.Nullable;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Resources.Binding;
import ru.pukpukov.pwrap.backends.nixos.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.Policy.Mode.*;

/** Builds one explicit namespace projection; no fallback grants or implicit HOME sharing. */
final class Payload {
    
    private static Map<String, String> bindings(List<Binding> bindings) {
        Map<String, String> files = new LinkedHashMap<>();
        bindings.forEach(b -> files.put(b.destination(), b.source()));
        return files;
    }
    
    static int run(Launch.Prepared p, Config config, Network.@Nullable Boundary boundary) throws Exception {
        return run(p, config, boundary, new Sandbox(config.platform));
    }
    
    static int run(Launch.Prepared p, Config config, Network.@Nullable Boundary boundary, Sandbox sandbox) throws Exception {
        Policy policy = p.policy();
        Policy.Mode mode = policy.network().mode();
        boolean local = policy.network().allowLocalhost() || mode == LOCAL, slirp = mode == INTERNET && !local;
        try (sandbox) {
            Path temp = sandbox.temp; List<String> args = sandbox.arguments; Session session = p.identity();
            String home = session.home(), runtime = session.runtime();
            String configHome = p.environment().getOrDefault("XDG_CONFIG_HOME", home + "/.config");
            String downloads = policy.files().downloads();
            // Only fresh namespace directories may host generated config. This
            // also excludes proc magic links and app-controlled shared scratch,
            // neither of which appears in the ordinary file-grant list.
            Set<String> mounted = new HashSet<>(List.of("/proc", "/dev"));
            mounted.addAll(p.runtime().directories());
            p.grants().forEach(g -> mounted.add(g.destination()));
            mounted.addAll(p.runtime().paths());
            p.runtime().files().forEach(b -> mounted.add(b.destination()));
            p.runtime().links().forEach(b -> mounted.add(b.destination()));
            mounted.addAll(p.desktop().files().keySet());
            p.desktop().links().forEach(b -> mounted.add(b.destination()));
            if (p.scratch() != null) mounted.add("/tmp");
            if (policy.integration().desktop() || policy.integration().opens().enabled()) {
                FileAuthority.privateDestination(configHome, mounted);
                FileAuthority.privateDestination(configHome + "/mimeapps.list", mounted);
            }
            if (policy.integration().desktop()) FileAuthority.privateDestination(configHome + "/glib-2.0/settings/keyfile", mounted);
            if (downloads != null) {
                FileAuthority.privateDestination(configHome, mounted);
                FileAuthority.privateDestination(configHome + "/user-dirs.dirs", mounted);
            }
            args.addAll(List.of("--hostname", "pwrap", "--dir", "/var/tmp", "--dir", home));
            if (runtime != null) args.addAll(List.of("--dir", runtime));
            for (String directory : p.runtime().directories()) args.addAll(List.of("--dir", directory));
            if (policy.network().sharesHost()) args.add("--share-net");
            if (p.scratch() != null) args.addAll(List.of("--bind", p.scratch(), "/tmp"));
            args.addAll(Sandbox.readOnly(p.runtime().paths()));
            args.addAll(Mounts.resources(bindings(p.runtime().files()), p.runtime().links(), p.runtime().paths()));
            args.addAll(List.of("--symlink", config.platform.path(SHELL), "/bin/sh", "--symlink", config.platform.path(ENV), "/usr/bin/env"));
            Map<String, String> generated = new LinkedHashMap<>(Map.of(
                "passwd", "root:x:0:0:Compartment:" + home + ":/bin/sh\n", "group", "root:x:0:\n", "hosts", "127.0.0.1 localhost\n::1 localhost\n",
                "nsswitch.conf", "passwd: files\ngroup: files\nhosts: files dns\n",
                "resolv.conf", slirp ? "nameserver " + Network.SLIRP_DNS + "\noptions timeout:2 attempts:2\n" :
                    mode == NONE || mode == LOCAL ? "" : required(boundary, "Remote networking requires a captured boundary").capture().resolverText()
            ));
            if (mode == LOCAL) generated.put("hosts", Files.readString(Path.of("/etc/hosts")));
            for (var entry : generated.entrySet()) {
                Path path = temp.resolve(entry.getKey());
                Files.writeString(path, entry.getValue());
                args.addAll(List.of("--ro-bind", path.toString(), "/etc/" + entry.getKey()));
            }
            args.addAll(List.of("--ro-bind", config.platform.path(CERT), "/etc/ssl/certs/ca-certificates.crt"));
            if (Files.exists(Path.of("/etc/localtime"))) args.addAll(List.of("--ro-bind", Fs.real("/etc/localtime"), "/etc/localtime"));
            sandbox.pin(p.grants(), home, policy.files().forbiddenWrites());
            Map<String, String> environment = new LinkedHashMap<>(p.environment());
            String executablePath = p.runtime().path();
            if (downloads != null) {
                Path directories = temp.resolve("user-dirs.dirs");
                Files.writeString(directories, Desktop.userDirectories(Fs.resolve(downloads, home)));
                args.addAll(List.of("--ro-bind", directories.toString(), configHome + "/user-dirs.dirs"));
            }
            if (policy.integration().desktop() || policy.integration().opens().enabled()) {
                for (String target : p.runtime().openerTargets()) {
                    args.addAll(List.of("--ro-bind", config.platform.path(PORTAL_TOOLS) + "/bin/xdg-open", target));
                }
                executablePath = config.platform.path(PORTAL_TOOLS) + "/bin:" + executablePath;
                List<String> types = Portals.mimeTypes(policy.integration().opens());
                Path share = temp.resolve("portal-desktop");
                Fs.mkdir(share.resolve("applications"));
                Files.writeString(share.resolve("applications/pwrap-portal.desktop"),
                    "[Desktop Entry]\nType=Application\nName=Desktop Portal\nExec=" +
                        config.platform.path(PORTAL_TOOLS) + "/bin/xdg-open %u\nMimeType=" +
                        String.join(";", types) + ";\nNoDisplay=true\n"
                );
                args.addAll(List.of("--ro-bind", share.toString(), "/run/pwrap-portal-data"));
                Path mime = temp.resolve("mimeapps.list");
                Files.writeString(mime, "[Default Applications]\n" + types.stream()
                    .map(t -> t + "=pwrap-portal.desktop;\n").collect(Collectors.joining())
                );
                args.addAll(List.of("--ro-bind", mime.toString(), configHome + "/mimeapps.list"));
                String data = environment.getOrDefault("XDG_DATA_DIRS", "");
                environment.put("XDG_DATA_DIRS", "/run/pwrap-portal-data:" + config.platform.path(PORTAL_TOOLS) + "/share" +
                    (data.isEmpty() ? "" : ":" + data)
                );
            }
            if (policy.integration().desktop()) {
                Path keyfile = temp.resolve("desktop-settings");
                Files.writeString(keyfile, p.desktop().settings());
                args.addAll(List.of("--ro-bind", keyfile.toString(), configHome + "/glib-2.0/settings/keyfile"));
                require(runtime != null, "Desktop access requires a captured session runtime directory");
                String display = environment.getOrDefault("WAYLAND_DISPLAY", "");
                String source = display.startsWith("/") ? display : runtime + "/" + display;
                require(!display.isEmpty() && Files.exists(Path.of(source)), "Wayland socket missing; refusing insecure X11 fallback");
                Path output = temp.resolve("wayland");
                Fs.mkdir(output);
                Posix.Child proxy = sandbox.wayland(source, output);
                long deadline = System.nanoTime() + 15_000_000_000L;
                while (!Files.exists(output.resolve("socket"))) {
                    require(proxy.poll() == null && System.nanoTime() < deadline, "Wayland filter did not become ready; no raw-socket fallback");
                    Thread.sleep(20);
                }
                args.addAll(List.of("--bind", output.resolve("socket").toString(), source));
                List<String> desktopMounts = new ArrayList<>(p.runtime().paths());
                p.grants().forEach(g -> desktopMounts.add(g.destination()));
                args.addAll(Mounts.resources(p.desktop().files(), p.desktop().links(), desktopMounts));
                if (environment.getOrDefault("FONTCONFIG_FILE", "").isEmpty()) {
                    args.addAll(List.of("--ro-bind", config.platform.path(FONT_CONFIG), "/etc/fonts/fonts.conf"));
                }
            }
            if (policy.integration().gpu()) {
                var projection = Graphics.materialize(required(p.graphics(), "GPU discovery was not captured"), temp.resolve("graphics"));
                for (Binding binding : projection.readOnly()) args.addAll(List.of("--ro-bind", binding.source(), binding.destination()));
                for (Binding binding : projection.devices()) args.addAll(List.of("--dev-bind", binding.source(), binding.destination()));
            }
            for (var file : p.audio().files().entrySet()) args.addAll(List.of("--ro-bind", file.getValue(), file.getKey()));
            if (policy.integration().bus()) {
                require(runtime != null, "D-Bus access requires a captured session runtime directory");
                Path output = temp.resolve("dbus");
                Fs.mkdir(output);
                var ready = sandbox.pipe();
                try {
                    sandbox.dbus(session, policy.integration().dbus(), Portals.arguments(policy.integration().opens()), output, ready[1]);
                } finally { ready[1].close(); }
                Sandbox.ready(ready[0], 1);
                args.addAll(List.of(
                    "--bind", output.resolve("bus").toString(), runtime + "/bus",
                    "--setenv", "DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime + "/bus"
                ));
            }
            Map<String, String> env = new LinkedHashMap<>(Map.of(
                "HOME", home, "USER", "root", "LOGNAME", "root", "PATH", executablePath,
                "SHELL", config.platform.path(SHELL), "TMPDIR", "/tmp", "SSL_CERT_FILE", config.platform.path(CERT),
                "FONTCONFIG_FILE", config.platform.path(FONT_CONFIG), "LOCALE_ARCHIVE", config.platform.path(LOCALE_ARCHIVE),
                "CURL_CA_BUNDLE", config.platform.path(CERT)
            ));
            env.putAll(environment);
            env.forEach((key, value) -> args.addAll(List.of("--setenv", key, value)));
            args.addAll(List.of("--chdir", p.cwd()));
            Posix.Fd[] info = null, block = null;
            if (slirp) {
                info = sandbox.pipe(); block = sandbox.pipe();
                sandbox.fds.add(info[1]);
                sandbox.fds.add(block[0]);
                args.addAll(List.of("--info-fd", "" + info[1].n, "--block-fd", "" + block[0].n));
            }
            Posix.Child app = sandbox.start(policy.command(), Sandbox.ipcPrefix(config.platform, policy.network()));
            if (slirp) {
                require(info != null && block != null, "Missing slirp namespace channels");
                configureSlirp(sandbox, config, required(boundary, "Slirp requires a captured network boundary"), info, block);
            }
            return app.waitFor();
        }
    }
    
    @Json.Extensible private record BwrapInfo(@SerializedName("child-pid") long childPid) {}
    
    private static void configureSlirp(Sandbox sandbox, Config config, Network.Boundary boundary, Posix.Fd[] info, Posix.Fd[] block) throws Exception {
        BwrapInfo document = decode(JsonParser.parseString(new String(info[0].readAll(65536, 15000), StandardCharsets.UTF_8)), BwrapInfo.class);
        info[0].close();
        long pid = document.childPid;
        require(pid > 0, "Invalid bubblewrap child PID");
        var ready = sandbox.pipe();
        Path resolver = sandbox.temp.resolve("host-resolv.conf");
        Files.writeString(resolver, boundary.capture().resolverText());
        try (
            var netns = Posix.open("/proc/" + pid + "/ns/net", Posix.O_RDONLY());
            var userns = Posix.open("/proc/" + pid + "/ns/user", Posix.O_RDONLY())
        ) {
            List<String> command = Commands.args(
                config.platform.path(SCOPE), config.platform.path(BWRAP),
                "--clearenv", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--new-session",
                "--proc", "/proc", "--tmpfs", "/proc/driver", "--dir", "/etc", "--dir", "/run",
                "--ro-bind", resolver.toString(), "/etc/resolv.conf", "--ro-bind", "/etc/hosts", "/etc/hosts",
                "--tmpfs", "/tmp", "--dev-bind", "/dev/net/tun", "/dev/net/tun",
                "--dev-bind", "/dev/urandom", "/dev/urandom", "--dev-bind", "/dev/null", "/dev/null",
                "--cap-add", "ALL", "--die-with-parent"
            );
            command.addAll(Sandbox.readOnly(Files.readAllLines(Path.of(config.platform.path(SLIRP_CLOSURE)))));
            command.addAll(List.of(
                "--", config.platform.path(SLIRP), "--configure", "--mtu=65520", "--disable-host-loopback",
                "--enable-seccomp", "--enable-sandbox", "--ready-fd=" + ready[1].n,
                "--netns-type=path", "--userns-path=" + userns.path(), netns.path(), "tap0"
            ));
            sandbox.own(Posix.spawn(command, System.getenv(), List.of(ready[1], netns, userns), 0, 1, 2));
        }
        ready[1].close();
        Sandbox.ready(ready[0], 1);
        ready[0].close();
        block[1].write(new byte[]{'1'});
        block[1].close();
    }
    
}