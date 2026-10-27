package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Actual sandbox/client/proxy handoff to an owned standard portal on a PRIVATE bus. */
final class HostUris {
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        Path session = Files.createDirectory(work.resolve("session")), output = Files.createDirectory(work.resolve("output"));
        String address = "unix:path=" + session.resolve("bus"), opener = runtime.config().platform.path(PlatformConfig.Key.PORTAL_TOOLS) + "/bin/xdg-open";
        Map<String, String> environment = Map.of("XDG_RUNTIME_DIR", session.toString(), "DBUS_SESSION_BUS_ADDRESS", address);
        Process daemon = start(work, "bus", List.of(RuntimeFixture.tool("dbus-daemon"), "--session", "--nofork", "--address=" + address), Map.of());
        try {
            await(() -> Files.exists(session.resolve("bus")), "Private portal bus not ready");
            Path fixture = payload.resolveSibling("pwrap-portal-fixture");
            require(Files.isExecutable(fixture), "Portal host test needs its adjacent test-only service: " + fixture);
            Process portal = start(work, "portal", List.of(fixture.toString(), address, output.toString()), Map.of());
            try {
                await(() -> Files.exists(output.resolve("ready")), "Owned standard portal fixture not ready");
                String tg = "tg://resolve?domain=fixture&start=--profile%20%2Froot", https = "https://example.invalid/pwrap?arg=--profile#fragment";
                int count = 0;
                for (String uri : List.of(tg, "mailto:fixture@example.invalid?subject=test")) {
                    String scheme = uri.substring(0, uri.indexOf(':'));
                    require(
                        run(work, scheme, runtime.command(List.of("run", "--open-uri", scheme, "--", opener, uri)), environment) == 0,
                        "Permitted standard URI handoff failed"
                    );
                    received(output, ++count, "OpenURI ask=false " + uri);
                }
                require(
                    run(work, "web", runtime.command(List.of("run", "--open-web", "--", opener, https)), environment) == 0,
                    "Standard web portal handoff failed"
                );
                received(output, ++count, "OpenURI ask=false " + https);
                Path file = write(work.resolve("тестовый файл.txt"), "owned fixture");
                for (String argument : List.of(file.toString(), file.toUri().toString())) {
                    require(run(work, "file", runtime.command(List.of(
                        "run", "--open-files", "--read", file.toString(), "--", opener, argument
                    )), environment) == 0, "Read-only file-FD portal handoff failed");
                    received(output, ++count, "OpenFile ask=true ");
                }
                require(run(work, "file-auto", runtime.command(List.of(
                    "run", "--open-files", "--no-file-ask", "--read", file.toString(), "--", opener, file.toString()
                )), environment) == 0, "Configured file ask=false was not enforced");
                received(output, ++count, "OpenFile ask=false ");
                require(
                    run(work, "web-ask", runtime.command(List.of("run", "--open-web", "--uri-ask", "--", opener, https)), environment) == 0,
                    "Configured web ask=true was not enforced"
                );
                received(output, ++count, "OpenURI ask=true " + https);
                for (String denied : List.of("mailto:fixture@example.invalid", file.toUri().toString(), https)) {
                    require(run(work, "deny", runtime.command(List.of(
                        "run", "--open-uri", "tg", "--read", file.toString(), "--", opener, denied
                    )), environment) != 0, "Caller-only portal capability was bypassed");
                }
                require(
                    run(work, "ungranted", runtime.command(List.of("run", "--", opener, https)), environment) != 0,
                    "Ungranted opener gained a session bus"
                );
                require(Files.readAllLines(output.resolve("calls")).size() == count, "Denied request reached the portal");
                packageOpeners(runtime, work, output, environment, https, count);
            } finally { stop(portal); }
        } finally { stop(daemon); }
        System.out.println(
            "PASS: real standard portal URI/file-FD handoff, file ask=true, no link chooser, " +
            "caller grants and packaged xdg-open replacement"
        );
    }
    
    private static void packageOpeners(Runtime runtime, Path work, Path output, Map<String, String> environment, String url, int count) throws Exception {
        String shell = runtime.config().platform.path(PlatformConfig.Key.SHELL);
        Path bin = Files.createDirectories(work.resolve("upstream/bin"));
        Path original = write(
            Fs.parent(bin).resolve("libexec/opener"),
            "#!" + shell + "\nprintf bypassed > \"$TEST_OUTPUT/bypass\"\nexit 29\n"
        );
        Fs.mode(original, 0755);
        Files.createSymbolicLink(bin.resolve("xdg-open"), Path.of("../libexec/opener"));
        Path immutable = store(runtime, Fs.parent(bin));
        var base = List.of(
            "run", "--runtime", immutable.resolve("bin/xdg-open").toString(),
            "--write", output.toString(), "--env", "TEST_OUTPUT=" + output
        );
        for (var command : List.of(
            List.of(immutable.resolve("bin/xdg-open").toString(), url),
            List.of(shell, "-c", "PATH=\"$1/bin:$PATH\"; xdg-open \"$2\"", "fixture", immutable.toString(), url)
        )) {
            require(
                run(work, "packaged", runtime.command(Commands.join(base, List.of("--open-web", "--"), command)), environment) == 0,
                "Package-prefixed opener bypassed the portal"
            );
            received(output, ++count, "OpenURI ask=false " + url);
            require(!Files.exists(output.resolve("bypass")), "Original package opener executed");
        }
        require(
            run(work, "original", runtime.command(Commands.join(
                base,
                List.of("--", immutable.resolve("bin/xdg-open").toString(), url)
            )), environment) == 29,
            "Explicit non-desktop, ungranted command was unexpectedly replaced"
        );
        require(Files.readString(output.resolve("bypass")).equals("bypassed"), "Original marker missing");
        require(
            Files.readString(immutable.resolve("libexec/opener")).equals(Files.readString(original)),
            "Mount replacement modified the immutable package"
        );
    }
    
    private static void received(Path output, int count, String line) throws IOException {
        var lines = Files.readAllLines(output.resolve("calls"));
        require(lines.size() == count && lines.getLast().equals(line), "Standard portal received altered/extra request");
    }
    
    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
    }
    
}