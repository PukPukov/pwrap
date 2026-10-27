package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Endpoint.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.*;
import com.google.gson.JsonObject;

class EgressTest {
    
    static List<Port> ports(int... values) { return Arrays.stream(values).mapToObj(Port::new).toList(); }
    
    static List<Address> addresses(String... values) { return Arrays.stream(values).map(Address::new).toList(); }
    
    static Policy.Routes network(String mode, boolean local, boolean lan) {
        return new Policy.Routes(Policy.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), local, lan);
    }
    
    @Test void test_deny_service_keeps_replies_but_rejects_originating_traffic() {
        assertEquals(List.of("ct state { invalid, untracked } counter drop", "ct direction reply return", "counter reject"),
            Egress.expressions(network("none", false, false), List.of(), List.of(), null, ports(53, 853))
        );
    }
    
    @Test void test_same_remote_rules_preserve_different_app_and_server_dot_contracts() {
        Policy.Routes p = network("internet", false, false);
        List<Address> dns = addresses("127.0.0.53", "2001:db8::53");
        var app = Egress.expressions(p, dns, List.of(), null, ports(53, 853));
        var server = Egress.expressions(p, dns, List.of(), null, ports(53));
        assertEquals(
            app.stream().filter(v -> !v.contains("th dport { 53, 853 }")).toList(),
            server.stream().filter(v -> !v.contains("th dport { 53 }")).toList()
        );
        assertTrue(app.indexOf("ip daddr 127.0.0.53 meta l4proto { tcp, udp } th dport 53 return") <
            app.indexOf("meta l4proto { tcp, udp } th dport { 53, 853 } counter reject")
        );
    }
    
    @Test void test_ip_literals_ports_and_nft_cgroup_identities_are_checked() {
        for (String ip : List.of("example.test", "::1%eth0", "127.0.0.1;", "127.1", "0127.0.0.1"))
            assertThrows(IllegalArgumentException.class, () -> new Address(ip));
        for (Object port : List.of(0, 65536, "53", true))
            assertThrows(IllegalArgumentException.class, () -> Policy.NetworkSettings.read(obj("localDeniedPorts", List.of(port))));
        for (String name : List.of("", "bad-name", "x;", "1x"))
            assertThrows(IllegalArgumentException.class, () -> Egress.table(new Egress.Name(name), Map.of(), -174, "pwrap egress"));
    }
    
    @Test void test_exclusions_precede_resolver_and_route_exceptions_for_all_modes() {
        for (String mode : List.of("internet", "lan", "local")) for (boolean local : List.of(false, true)) {
            var rules = Egress.expressions(
                network(mode, local, true), mode.equals("local") ? List.of() : addresses("127.0.0.53", "::1"),
                ports(3128), new Port(54321), ports(53, 853)
            );
            int explicit = -1, baseline = -1, canary = -1;
            for (int i = 0; i < rules.size(); i++) {
                String rule = rules.get(i);
                if (rule.contains("3128")) explicit = i;
                if (rule.contains("{ 53, 853 }")) baseline = i;
                if (rule.contains("54321")) canary = i;
            }
            assertTrue(canary < explicit && explicit < baseline);
            for (int i = 0; i < rules.size(); i++)
                if (rules.get(i).endsWith(" return") && !rules.get(i).contains("ct direction reply")) assertTrue(explicit < i);
        }
    }
    
    @Test void canonical_ip_literals_match_python() {
        assertEquals("2001:db8::53", new Address("2001:0DB8:0:0:0:0:0:53").literal());
        assertEquals("::ffff:7f00:1", new Address("::ffff:127.0.0.1").literal());
        assertEquals("::", new Address("0:0:0:0:0:0:0:0").literal());
    }
    
}