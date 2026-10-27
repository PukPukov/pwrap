package ru.pukpukov.pwrap.host;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import ru.pukpukov.pwrap.core.*;

/** Actual Nix-generated direct tasks; fixtures never overlap a real HOME. */
public final class HostMaintenance {
    
    private record Fixture(String testRoot, String selected, Map<String, String> tasks) {}
    
    public static void check(Path document) throws Exception {
        Fixture fixture = Json.read(document, Fixture.class);
        Path work = Path.of(fixture.testRoot), selected = Path.of(fixture.selected);
        require(
            Posix.geteuid() == 0 && work.toString().startsWith("/var/tmp/pwrap-maintenance-test-") &&
                !Files.exists(work, LinkOption.NOFOLLOW_LINKS) && selected.equals(work.resolve("shared/parent/app")) &&
                fixture.tasks.keySet().equals(Set.of("read", "write", "create")), "Fresh synthetic maintenance fixture required"
        );
        Files.createDirectory(work);
        Files.createDirectories(selected);
        write(work.resolve("victim/canary"), "protected");
        Map<String, String> environment = Map.of("PWRAP_MAINTENANCE_ALLOWED", "explicit", "PWRAP_MAINTENANCE_SECRET", "not-inherited");
        for (String mode : List.of("read", "write")) {
            Path canary = write(selected.resolve("canary"), "original");
            var command = List.of(required(fixture.tasks.get(mode), "Missing task"), "one argument", "");
            require(
                run(work, mode, command, environment) == 0 &&
                    Files.readString(canary).equals(mode.equals("write") ? "only-original-changed" : "original"),
                "Generated file task changed argv/env/permission contract"
            );
            for (Path denied : List.of(selected, Fs.parent(selected))) {
                int oldMode = ((Number) Files.getAttribute(denied, "unix:mode")).intValue() & 07777;
                Files.setAttribute(denied, "unix:uid", 65534);
                Fs.mode(denied, 0700);
                try { require(run(work, mode + "-private", command, environment) != 0, "Generated task accepted a DAC-private source/parent"); }
                finally {
                    Files.setAttribute(denied, "unix:uid", 0);
                    Fs.mode(denied, oldMode);
                }
            }
        }
        Files.delete(selected.resolve("canary"));
        Files.delete(selected);
        Path parent = Fs.parent(selected);
        Files.setAttribute(parent, "unix:uid", 65534);
        Fs.mode(parent, 0700);
        try {
            require(
                run(work, "private-create", List.of(required(fixture.tasks.get("create"), "Missing create task")), environment) != 0 &&
                    !Files.exists(selected), "Generated persist task created under private parent"
            );
        }
        finally {
            Files.setAttribute(parent, "unix:uid", 0);
            Fs.mode(parent, 0755);
        }
        require(
            run(work, "create", List.of(required(fixture.tasks.get("create"), "Missing create task")), environment) == 0 &&
                Files.readString(selected.resolve("created")).equals("created") &&
                Files.readString(work.resolve("victim/canary")).equals("protected"), "Generated persist task failed or changed sibling data"
        );
        System.out.println(
            "PASS: actual generated read/write/persist tasks, cap0/seccomp/argv/env/FDs and " +
                "private source/parent refusal; evidence " + work
        );
    }
}