package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nixos.NativeProjectionChecks;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Test-owned protocol helper: no calls to real services or driver controls. */
final class HostGraphics {
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        NativeProjectionChecks.graphics(runtime.config(), payload, Files.createDirectory(work.resolve("projection")));
        Path bus = Path.of(System.getenv().getOrDefault("XDG_RUNTIME_DIR", "/run/user/0"), "bus");
        if (!Files.exists(bus)) {
            System.out.println("SKIP: graphics/D-Bus helper output isolation (no captured session bus)");
            return;
        }
        String name = "graphics-helper-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var policy = obj(
            "name", name, "gpu", true, "dbusOwn", List.of("org.pwrap.GraphicsFixture"),
            "command", List.of(payload.toString(), "_payload", "SLEEP", "30000")
        );
        Path manifest = write(work.resolve("profile.json"), policy.toString());
        Process child = start(work, "launch", runtime.command(List.of("profile", manifest.toString())), Map.of());
        String unit = "";
        try {
            Path registry = Path.of("/run/pwrap-instances", name);
            await(() -> !glob(registry, "*.json").isEmpty(), "Graphics helper fixture did not register: " + work);
            unit = unit(registry);
            String heldUnit = unit;
            await(() -> outputIsolated(heldUnit), "D-Bus helper did not publish an isolated output socket: " + work);
            System.out.println("PASS: actual D-Bus helper sees only its output socket, not graphics/provider backing files");
        } finally {
            if (!unit.isEmpty()) stop(runtime, unit);
            if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
        }
    }
    
    private static boolean outputIsolated(String unit) throws IOException {
        Path group = Path.of("/sys/fs/cgroup/system.slice", unit);
        if (!Files.exists(group)) return false;
        try (var groups = Files.walk(group)) {
            for (Path processes : groups.filter(p -> Fs.name(p).equals("cgroup.procs")).toList()) {
                for (String pid : Files.readString(processes).split("\\s+")) {
                    if (pid.isEmpty()) continue;
                    Path process = Path.of("/proc", pid);
                    try {
                        if (!Files.readString(process.resolve("comm")).strip().equals("xdg-dbus-proxy")) continue;
                        Path output = process.resolve("root/out");
                        if (!Files.exists(output.resolve("bus"))) continue;
                        try (var files = Files.list(output)) {
                            require(files.map(Fs::name).sorted().toList().equals(List.of("bus")),
                                "D-Bus helper can modify another provider's backing files"
                            );
                        }
                        return true;
                    } catch (NoSuchFileException exited) { }
                }
            }
        }
        return false;
    }
    
}