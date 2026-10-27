package ru.pukpukov.pwrap.core;

import module java.base;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreBoundaryTest {
    
    @Test void core_has_no_concrete_sandbox_or_backend_dependencies() throws IOException {
        Path root = Path.of("home/code/src/main/java/ru/pukpukov/pwrap");
        assertFalse(Files.exists(root.resolve("core/Sandbox.java")));
        assertFalse(Files.exists(root.resolve("core/Config.java")));
        assertTrue(Files.exists(root.resolve("backends/nixos/Sandbox.java")));
        try (var sources = Files.list(root.resolve("core"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList())
                assertTrue(
                    Files.readString(source).lines()
                        .filter(s -> s.startsWith("import "))
                        .noneMatch(s -> s.contains("pwrap.backends.") || s.matches(
                            "import (static )?ru\\.pukpukov\\.pwrap\\.(Config|Launch|Payload|Main|Activation).*"
                        )),
                    source.toString()
                );
        }
        String authority = Files.readString(root.resolve("core/FileAuthority.java"));
        for (String option : List.of("--bind-fd", "--ro-bind-fd")) assertFalse(authority.contains(option));
        assertFalse(authority.contains("setpriv"));
        assertFalse(authority.contains("Config config"));
        String instances = Files.readString(root.resolve("core/Instances.java"));
        for (String implementation : List.of("system.slice", "cgroup.events", "/proc", "pwrap-", "\\\\.service"))
            assertFalse(instances.contains(implementation));
        assertFalse(Files.exists(root.resolve("core/Egress.java")));
        assertFalse(Files.readString(root.resolve("core/Json.java")).contains("Egress"));
    }
    
}