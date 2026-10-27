package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.Endpoint.*;

/** NativeInstallTests from test_egress.py: shell fixture records actual subprocess transactions. */
class ServiceEgressTest {
    
    final Path root, calls, transaction, old, tool;
    int captures;
    ServiceEgressTest(@TempDir Path root) throws IOException {
        this.root = root;
        calls = root.resolve("calls"); transaction = root.resolve("transaction"); old = root.resolve("old");
        tool = script(
            root.resolve("nft"), """
            printf '%%s\\n' "$*" >> %s
            case "$1" in
              --json) [ -e %s ] || exit 1; while IFS= read -r line; do printf '%%s\\n' "$line"; done < %s;;
              --file) while IFS= read -r line; do printf '%%s\\n' "$line"; done > %s; [ ! -e %s ];;
              *) exit 64;;
            esac
            """.formatted(quote(calls), quote(old), quote(old), quote(transaction), quote(root.resolve("reject")))
        );
    }
    
    private static ServiceEgress.Service service(Policy.Mode mode, List<Address> dns, String group) {
        return new ServiceEgress.Service(mode, dns, new Egress.Cgroup("system.slice/" + group));
    }
    private void install(Map<Egress.Name, ServiceEgress.Service> policies) throws Exception {
        ServiceEgress.install(tool.toString(), new Egress.Name("fixture"), policies, () -> {
            captures++;
            return new Egress.Resolver("nameserver 127.0.0.53\n", List.of(new Address("127.0.0.53")));
        });
    }
    
    private Map<Egress.Name, ServiceEgress.Service> policies() {
        return Map.of(
            new Egress.Name("deny"), service(Policy.Mode.NONE, List.of(), "system-deny.slice"),
            new Egress.Name("internet"), service(Policy.Mode.INTERNET, List.of(new Address("127.0.0.53")), "system-internet.slice"),
            new Egress.Name("second"), service(Policy.Mode.INTERNET, List.of(new Address("127.0.0.53")), "system-second.slice")
        );
    }
    
    @Test void test_resolver_is_captured_once_and_install_is_one_atomic_transaction() throws Exception {
        Files.writeString(old, obj("nftables", List.of(
            obj("metainfo", obj()),
            obj("table", obj("family", "inet", "name", "fixture", "comment", "pwrap native services"))
        )).toString() + "\n");
        install(policies());
        assertEquals(List.of("--json list table inet fixture", "--file -"), Files.readAllLines(calls));
        String rules = Files.readString(transaction);
        assertTrue(rules.startsWith("delete table inet fixture\ncreate table inet fixture"));
        assertTrue(rules.contains("add rule inet fixture deny counter reject"));
        assertFalse(rules.contains("deny ip daddr 127.0.0.53"));
        assertEquals(2, rules.lines().filter(s -> s.contains("daddr 127.0.0.53")).count());
        assertFalse(rules.contains("853"));
        // Shared resolver capture itself is exercised below with two inheriting services.
        Map<Egress.Name, ServiceEgress.Service> inherited = new LinkedHashMap<>();
        inherited.put(new Egress.Name("first"), new ServiceEgress.Service(
            Policy.Mode.INTERNET, null, new Egress.Cgroup("system.slice/first.service")
        ));
        inherited.put(new Egress.Name("second"), new ServiceEgress.Service(
            Policy.Mode.INTERNET, null, new Egress.Cgroup("system.slice/second.service")
        ));
        install(inherited);
        assertEquals(1, captures);
    }
    
    @Test void test_explicit_dns_and_empty_dns_do_not_discover_system_resolver() throws Exception {
        for (var policy : List.of(
            service(Policy.Mode.NONE, List.of(), "test.service"),
            service(Policy.Mode.INTERNET, List.of(), "test.service"),
            service(Policy.Mode.INTERNET, List.of(new Address("::1")), "test.service")
        )) {
            install(Map.of(new Egress.Name("sample"), policy));
            String text = Files.readString(transaction);
            assertEquals(
                Json.required(policy.dns(), "Explicit fixture DNS").isEmpty() ? 0 : 1,
                text.lines().filter(s -> s.contains("th dport 53 return")).count()
            );
        }
        ServiceEgress.Service nullableDns = Json.decode(
            JsonParser.parseString("{\"network\":\"internet\",\"dns\":null,\"cgroup\":\"system.slice/test.service\"}"),
            ServiceEgress.Service.class
        );
        assertNull(nullableDns.dns());
        assertEquals(0, captures);
    }
    
    @Test void test_unknown_table_and_invalid_capture_fail_before_applying() throws Exception {
        for (JsonObject value : List.of(
            obj("nftables", List.of(obj("table", obj("comment", "someone else")))),
            obj("nftables", List.of()),
            obj("nftables", List.of(obj("table", obj())))
        )) {
            Files.writeString(old, value.toString() + "\n");
            assertThrows(IllegalArgumentException.class, () -> install(policies()));
            assertFalse(Files.exists(transaction));
            assertEquals(1, Files.readAllLines(calls).size());
            Files.delete(calls);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.decode(
            JsonParser.parseString("{\"network\":\"internet\",\"dns\":[\"not an IP\"],\"cgroup\":\"system.slice/test.service\"}"),
            ServiceEgress.Service.class
        ));
        assertFalse(Files.exists(calls));
    }
    
    @Test void test_kernel_rejection_is_not_retried_with_empty_or_weaker_rules() throws Exception {
        Files.writeString(root.resolve("reject"), "");
        assertThrows(IOException.class, () -> install(policies()));
        assertEquals(2, Files.readAllLines(calls).size());
        assertTrue(Files.readString(transaction).contains("counter reject"));
    }
    
}