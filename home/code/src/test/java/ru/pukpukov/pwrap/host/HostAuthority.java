package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.Config;
import ru.pukpukov.pwrap.backends.nixos.Sandbox;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Benign selected-inode/permission regressions; no vulnerable-path control. */
final class HostAuthority {
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        Path privateDir = Files.createDirectory(work.resolve("private"));
        Path canary = write(privateDir.resolve("secret"), "unchanged");
        Path alias = Files.createSymbolicLink(work.resolve("alias"), privateDir);
        Path output = Files.createDirectory(work.resolve("output"));
        Path marker = output.resolve("unexpected-start");
        var command = List.of(payload.toString(), "_payload", "WRITE", marker.toString(), "unexpected");
        for (String permission : List.of("--read", "--write", "--persist")) for (Path path : List.of(alias, alias.resolve("secret")))
            require(run(work, "alias-" + permission.substring(2), runtime.command(Commands.join(
                List.of("run", permission, path.toString(), "--write", output.toString(), "--"), command
            ))) != 0 && !Files.exists(marker), "Ordinary alias granted authority");
        require(run(work, "persist-alias", runtime.command(Commands.join(
            List.of("run", "--persist", alias.resolve("new/deep").toString(), "--write", output.toString(), "--"), command
        ))) != 0 && !Files.exists(privateDir.resolve("new")) && Files.readString(canary).equals("unchanged"), "Alias persist created private state");
        Path foreign = write(work.resolve("foreign-0600"), "private fixture");
        Fs.mode(foreign, 0600);
        Files.setAttribute(foreign, "unix:uid", 65534);
        Path parent = Files.createDirectory(work.resolve("foreign-0700"));
        Path child = write(parent.resolve("public"), "public leaf");
        Fs.mode(parent, 0700);
        Fs.mode(child, 0644);
        Files.setAttribute(parent, "unix:uid", 65534);
        try {
            for (Path path : List.of(foreign, child)) {
                String label = path.equals(foreign) ? "foreign-file" : "foreign-parent";
                require(run(work, label, runtime.command(Commands.join(
                    List.of("run", "--read", path.toString(), "--write", output.toString(), "--"), command
                ))) != 0 && !Files.exists(marker) &&
                    Files.readString(work.resolve(label + ".log")).contains("Permission denied"),
                "Private grant did not fail through DAC preflight");
            }
            require(run(work, "foreign-persist", runtime.command(Commands.join(
                List.of("run", "--persist", parent.resolve("missing").toString(), "--write", output.toString(), "--"), command
            ))) != 0 && !Files.exists(parent.resolve("missing")), "Privileged persist created state under foreign-private parent");
        } finally {
            Files.setAttribute(foreign, "unix:uid", 0);
            Files.setAttribute(parent, "unix:uid", 0);
        }
        Path immutable = store(runtime, write(work.resolve("immutable-input"), "immutable data"));
        Path controller = Files.createSymbolicLink(work.resolve("controller"), immutable);
        require(run(work, "explicit-immutable", runtime.command(List.of(
            "run", "--immutable-data", controller.toString(), "--", payload.toString(), "_payload", "READ",
            controller.toString(), "immutable data"
        ))) == 0, "Explicit immutable alias no longer resolves");
        require(run(work, "not-immutable", runtime.command(Commands.join(
            List.of("run", "--immutable-data", alias.resolve("secret").toString(), "--write", output.toString(), "--"), command
        ))) != 0 && Files.readString(work.resolve("not-immutable.log")).contains("not immutable") && !Files.exists(marker),
        "Mutable path accepted as immutable");
        Path home = Files.createDirectory(work.resolve("session-home"));
        require(run(work, "session-home", runtime.command(List.of(
            "run", "--", payload.toString(), "_payload", "ENVIRONMENT", "HOME", home.toString()
        )), Map.of("HOME", home.toString(), "XDG_RUNTIME_DIR", work.resolve("session-runtime").toString())) == 0,
        "Caller session HOME lost through manager");
        require(run(work, "whole-home", runtime.command(Commands.join(
            List.of("run", "--read", home.toString(), "--write", output.toString(), "--"), command
        )), Map.of("HOME", home.toString())) != 0 &&
            Files.readString(work.resolve("whole-home.log")).contains("whole-home"),
        "Ambient service HOME replaced captured whole-home guard");
        for (boolean writable : List.of(false, true)) {
            pinned(runtime.config(), payload, Files.createDirectory(work.resolve(writable ? "pinned-write" : "pinned-read")), writable);
        }
        System.out.println("PASS: real no-follow/DAC/persist/session boundaries and pinned-inode bwrap mounts without a vulnerable control");
    }

    private static void pinned(Config config, Path payload, Path work, boolean writable) throws Exception {
        Path selected = Files.createDirectory(work.resolve("selected"));
        Path original = write(selected.resolve("canary"), "original");
        Path moved = work.resolve("retired");
        try (var box = new Sandbox(config.platform, new Lifetime(work))) {
            var roots = new TreeSet<>(NixStorage.readClosure(config.platform.path(PlatformConfig.Key.BASE_CLOSURE)));
            roots.addAll(NixStorage.closure(List.of(payload.toString()), config.nix()));
            box.arguments.addAll(Sandbox.readOnly(roots));
            box.pin(List.of(new FileAuthority.Grant(selected.toString(), "/data", writable)), "/fixture-home", List.of());
            Files.move(selected, moved);
            Files.createDirectory(selected);
            write(selected.resolve("canary"), "replacement");
            var process = box.start(List.of(payload.toString(), "_payload", "PINNED", Boolean.toString(writable)), List.of());
            HostLifetime.await(() -> process.poll() != null, "Pinned mount payload timed out");
            require(process.waitFor() == 0, "Pinned mount payload failed");
        }
        require(Files.readString(moved.resolve(Fs.name(original))).equals(writable ? "only-original-changed" : "original") &&
            Files.readString(selected.resolve("canary")).equals("replacement"),
        "Pinned mount selected replacement instead of acquired inode");
    }
    
}