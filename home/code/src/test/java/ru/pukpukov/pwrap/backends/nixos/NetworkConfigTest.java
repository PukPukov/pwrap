package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.*;
import static ru.pukpukov.pwrap.backends.nixos.EgressTest.ports;

/** Same inputs and assertions as test_network_config.py; queries replace subprocess patching. */
class NetworkConfigTest {
    
    final Path root;
    
    NetworkConfigTest(@TempDir Path root) { this.root = root; }
    
    private PlatformConfig config(JsonObject metadata) {
        JsonObject raw = obj(
            "storageBackend", "nix", "platformBackend", "nixos", "entry", "/entry",
            "hostConfigDir", root.toString(), "metadata", metadata, "waylandInterfaces", List.of()
        );
        for (PlatformConfig.Key key : PlatformConfig.Key.values()) raw.addProperty(key.json, "/tools/" + key.json);
        return new PlatformConfig(raw);
    }
    
    @Test void test_manifest_network_settings_override_runner_metadata() throws Exception {
        PlatformConfig config = config(obj("network", obj("localDeniedPorts", List.of(3128))));
        assertEquals(ports(2080), Network.deniedPorts(config, Policy.NetworkSettings.read(obj("localDeniedPorts", List.of(2080))), root));
    }
    
    @Test void test_invalid_manifest_network_settings_fail_before_launch_even_offline() {
        for (JsonObject settings : List.of(
            obj("typo", List.of()), obj("localDeniedPorts", List.of(0)), obj("relayServices", List.of("..bad.service"))
        ))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                "command", List.of("app"), "network", "none", "networkConfig", settings
            )));
    }
    
    @Test void test_reads_actual_resolver_instead_of_assuming_localhost() throws Exception {
        Path path = root.resolve("resolv.conf");
        String text = "search lan.example\nnameserver 127.0.0.53\nnameserver 2001:db8::53 # stub\noptions edns0\n";
        Files.writeString(path, text);
        Egress.Resolver resolver = Egress.Resolver.read(path);
        assertEquals(text, resolver.text());
        assertEquals(List.of(new Endpoint.Address("127.0.0.53"), new Endpoint.Address("2001:db8::53")), resolver.addresses());
    }
    
    @Test void test_resolver_is_never_replaced_with_public_or_implicit_dns() throws Exception {
        Path path = root.resolve("resolv.conf");
        for (String contents : List.of("", "nameserver attacker.example\n", "nameserver fe80::1%eth0\n")) {
            Files.writeString(path, contents);
            assertThrows(IllegalArgumentException.class, () -> Egress.Resolver.read(path));
        }
    }
    
    @Test void test_no_installed_vpn_dependency() throws Exception {
        PlatformConfig config = config(obj());
        assertEquals(List.of(), Network.deniedPorts(config, config.network(), root));
    }
    
    @Test void test_relay_configuration_types_fail_closed() throws Exception {
        PlatformConfig config = config(obj());
        for (JsonObject invalid : List.of(
            obj("localDeniedPorts", List.of(true)), obj("localDeniedPorts", List.of(0)),
            obj("relayServices", List.of("../other.service")), obj("typo", List.of())
        )) {
            Files.writeString(root.resolve("network.json"), invalid.toString());
            assertThrows(IllegalArgumentException.class, config::network);
        }
    }
    
    @Test void test_all_service_process_listeners_guarded_not_only_one_executable() throws Exception {
        Files.writeString(root.resolve("network.json"), obj(
            "relayServices", List.of("my-proxy.service"), "localDeniedPorts", List.of(53, 3128)
        ).toString());
        Path group = root.resolve("cgroups/system.slice/my-proxy.service/child");
        Files.createDirectories(group);
        Files.writeString(group.resolve("cgroup.procs"), "123\n456\n");
        Deque<String> outputs = new ArrayDeque<>(List.of(
            "/system.slice/my-proxy.service\n",
            "tcp LISTEN 0 4096 127.0.0.1:43210 0.0.0.0:* users:((\"anything\",pid=456,fd=3))\n"
        ));
        PlatformConfig config = config(obj());
        assertEquals(ports(53, 3128, 43210), Network.deniedPorts(
            config, config.network(), root.resolve("cgroups"), _ -> outputs.removeFirst()
        ));
        assertTrue(outputs.isEmpty());
    }
    
}