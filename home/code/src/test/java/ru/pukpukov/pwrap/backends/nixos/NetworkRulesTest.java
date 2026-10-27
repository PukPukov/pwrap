package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.Endpoint.*;
import org.junit.jupiter.api.Test;
import ru.pukpukov.pwrap.core.*;

/** Direct assertions from test_network.py and the compiler half of test_endpoint_rules.py. */
class NetworkRulesTest {
    
    private static Policy.Routes route(boolean local, boolean lan) { return new Policy.Routes(Policy.Mode.LOCAL, local, lan); }
    
    private static List<String> rules(Policy.Mode mode, boolean loopback, List<Port> denied) {
        return Egress.expressions(
            new Policy.Routes(mode, loopback, true),
            mode == Policy.Mode.LOCAL ? List.of() : List.of(new Address("127.0.0.53"), new Address("::1")),
            denied, new Port(54321), List.of(Port.DNS, Port.DOT)
        );
    }
    
    @Test void test_absent_grants_never_authorize_loopback_or_lan() {
        assertEquals(List.of(), Egress.localPrefixes(route(false, false)));
        assertEquals(Egress.LAN, Egress.localPrefixes(route(false, true)));
        assertEquals(Egress.LOOPBACK, Egress.localPrefixes(route(true, false)));
    }
    
    @Test void test_grouped_dsl_local_grants_are_independent() {
        assertEquals(Egress.LOOPBACK, Egress.localPrefixes(route(true, false)));
        assertEquals(Egress.LAN, Egress.localPrefixes(route(false, true)));
        assertEquals(Commands.join(Egress.LOOPBACK, Egress.LAN), Egress.localPrefixes(route(true, true)));
    }
    
    @Test void test_host_principal_is_not_a_subnet_grant() {
        for (var mode : List.of(Policy.Mode.LOCAL, Policy.Mode.INTERNET, Policy.Mode.LAN)) for (boolean loopback : List.of(false, true)) {
            var rules = rules(mode, loopback, List.of());
            var host = rules.stream().filter(s -> s.endsWith(" return") && s.contains("fib daddr type")).toList();
            assertEquals(loopback ? 2 : 0, host.size());
            assertTrue(host.stream().noneMatch(s -> s.contains("10.0.0.0/8") || s.contains("fc00::/7")));
        }
    }
    
    @Test void test_principal_guard_precedes_every_positive_subnet_route() {
        for (var mode : List.of(Policy.Mode.LOCAL, Policy.Mode.INTERNET, Policy.Mode.LAN)) for (boolean loopback : List.of(false, true)) {
            var lines = rules(mode, loopback, List.of());
            int guard = lines.indexOf("fib daddr type { local, anycast } counter reject");
            assertTrue(guard >= 0);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.endsWith(" return") || !line.contains("daddr") || line.contains("th dport 53")) continue;
                assertTrue(line.contains("fib daddr type") ? i < guard : i > guard, line);
            }
        }
    }
    
    @Test void test_shared_guards_precede_all_positive_routes_and_dns_is_narrow() {
        for (var mode : List.of(Policy.Mode.LOCAL, Policy.Mode.INTERNET, Policy.Mode.LAN)) for (boolean loopback : List.of(false, true)) {
            var lines = rules(mode, loopback, List.of(new Port(3128)));
            int explicit = -1, baseline = -1, canary = -1;
            List<Integer> dns = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("3128")) explicit = i;
                if (line.contains("53, 853")) baseline = i;
                if (line.contains("54321")) canary = i;
                if (line.contains("th dport 53 return")) dns.add(i);
            }
            assertTrue(canary >= 0 && canary < explicit && explicit < baseline);
            assertFalse(lines.get(baseline).contains("fib daddr type"));
            assertEquals(mode == Policy.Mode.LOCAL ? 0 : 2, dns.size());
            for (int i : dns) {
                assertTrue(explicit < i && i < baseline);
                assertTrue(lines.get(i).contains("meta l4proto { tcp, udp }"));
            }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).endsWith(" return") && !lines.get(i).contains("ct direction reply")) {
                    assertTrue(explicit < i);
                }
            }
        }
    }
    
    @Test void test_explicit_dns_port_deny_cannot_be_overruled_by_resolver_exception() {
        var lines = rules(Policy.Mode.INTERNET, true, List.of(Port.DNS));
        assertTrue(
            lines.indexOf("fib daddr type { local, anycast } meta l4proto { tcp, udp } th dport { 53 } counter reject")
                < lines.indexOf("ip daddr 127.0.0.53 meta l4proto { tcp, udp } th dport 53 return")
        );
    }
    
}