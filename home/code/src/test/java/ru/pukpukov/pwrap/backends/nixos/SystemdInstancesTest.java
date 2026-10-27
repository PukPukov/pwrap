package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SystemdInstancesTest {
    
    @Test void liveness_is_strict_and_missing_events_cannot_mean_dead(@TempDir Path root) throws Exception {
        var executions = new SystemdInstances(root.resolve("groups"), root.resolve("proc"));
        String identity = "pwrap-test.service";
        assertFalse(executions.active(identity));
        Path group = Files.createDirectories(root.resolve("groups/" + identity)), events = group.resolve("cgroup.events");
        assertThrows(IllegalArgumentException.class, () -> executions.active(identity));
        for (String malformed : List.of("", "populated 2\n", "populated 0\npopulated 1\n", "populated\n")) {
            Files.writeString(events, malformed);
            assertThrows(IllegalArgumentException.class, () -> executions.active(identity));
        }
        for (boolean active : List.of(false, true)) {
            Files.writeString(events, "populated " + (active ? "1" : "0") + "\nfrozen 0\n");
            assertEquals(active, executions.active(identity));
        }
    }
    
    @Test void peer_membership_is_exact_with_children_not_similar_names(@TempDir Path root) throws Exception {
        var executions = new SystemdInstances(root.resolve("groups"), root.resolve("proc"));
        String identity = "pwrap-test.service";
        assertFalse(executions.contains(identity, 42));
        Path cgroup = Files.createDirectories(root.resolve("proc/42")).resolve("cgroup");
        for (String path : List.of("/system.slice/" + identity, "/system.slice/" + identity + "/child")) {
            Files.writeString(cgroup, "0::" + path + "\n");
            assertTrue(executions.contains(identity, 42));
        }
        for (String path : List.of("/system.slice/" + identity + "-similar", "/user.slice/" + identity)) {
            Files.writeString(cgroup, "0::" + path + "\n");
            assertFalse(executions.contains(identity, 42));
        }
        Files.writeString(cgroup, "0::/one\n0::/two\n");
        assertThrows(IllegalArgumentException.class, () -> executions.contains(identity, 42));
        assertThrows(IllegalArgumentException.class, () -> executions.contains(identity, 0));
    }
    
    @Test void identities_remain_platform_validated(@TempDir Path root) {
        var executions = new SystemdInstances(root, root);
        for (String invalid : List.of("../pwrap-test.service", "other.service", "worker-one", "pwrap-test.service/child")) {
            assertThrows(IllegalArgumentException.class, () -> executions.active(invalid));
            assertThrows(IllegalArgumentException.class, () -> executions.contains(invalid, 42));
        }
    }
    
}