package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.Endpoint.*;

/** test_network_boundary.py, with actual native lease locks and typed capture injection. */
@EnabledInNativeImage
class NetworkBoundaryTest {
    
    final Path root;
    final Instances instances;
    final PlatformConfig config;
    final Policy policy;
    Network.Capture captured = new Network.Capture(List.of(new Port(3128), new Port(40000)), "nameserver 127.0.0.53\n", List.of(new Address("127.0.0.53")));
    int captures;
    
    NetworkBoundaryTest(@TempDir Path root) {
        this.root = root;
        instances = SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
        config = RuntimeFixture.platform(root.resolve("absent"), obj());
        policy = Policy.read(input());
    }
    
    private static JsonObject input() {
        return obj(
            "name", "app", "command", List.of("/app"), "network", "internet", "allowLocalhost", true,
            "networkConfig", obj("localDeniedPorts", List.of(3128), "relayServices", List.of("relay.service"))
        );
    }
    
    private Network.Frozen freeze(Policy p, PlatformConfig c) throws Exception {
        return Network.freeze(p, c, instances, () -> {
            captures++;
            return captured;
        });
    }
    
    @Test void test_all_network_discovery_belongs_to_one_cohort_then_refreshes() throws Exception {
        Network.Capture previous = captured;
        try (var first = freeze(policy, config)) {
            captured = new Network.Capture(List.of(new Port(3128), new Port(50000)), "nameserver ::1\n", List.of(new Address("::1")));
            try (var second = freeze(policy, config)) {
                assertEquals(first.boundary(), second.boundary());
                assertEquals(previous, second.boundary().capture());
                assertEquals(1, captures);
                assertEquals(first.boundary(), Json.decode(Json.encode(first.boundary()), Network.Boundary.class));
            }
        }
        try (var fresh = freeze(policy, config)) {
            assertEquals(captured, fresh.boundary().capture());
            assertEquals(2, captures);
        }
    }
    
    @Test void test_every_network_intent_change_rejects_borrowing() throws Exception {
        try (var first = freeze(policy, config)) {
            assertEquals(1, captures);
            for (JsonObject update : List.of(
                obj("network", "lan"), obj("allowLocalhost", false),
                obj("networkConfig", obj("localDeniedPorts", List.of(9999), "relayServices", List.of("relay.service"))),
                obj("networkConfig", obj("localDeniedPorts", List.of(3128), "relayServices", List.of("different.service")))
            )) {
                JsonObject input = input();
                update.entrySet().forEach(e -> input.add(e.getKey(), e.getValue()));
                assertThrows(IllegalArgumentException.class, () -> freeze(Policy.read(input), config));
            }
            assertEquals(1, captures);
            assertEquals(policy.network(), first.boundary().request().network());
        }
    }
    
    @Test void test_prepared_consumer_requires_matching_explicit_intent() throws Exception {
        try (var first = freeze(policy, config)) {
            first.boundary().verify(policy.network(), config);
            for (JsonObject update : List.of(
                obj("network", "lan"), obj("allowLocalhost", false), obj("networkConfig", obj("localDeniedPorts", List.of(9999)))
            )) {
                JsonObject input = input();
                update.entrySet().forEach(e -> input.add(e.getKey(), e.getValue()));
                assertThrows(IllegalArgumentException.class, () -> first.boundary().verify(Policy.read(input).network(), config));
            }
        }
    }
    
    @Test void test_typed_document_has_no_wrong_type_or_legacy_field_fallback() throws Exception {
        try (var first = freeze(policy, config)) {
            for (JsonObject change : List.of(
                obj("deniedPorts", List.of(false)), obj("deniedPorts", List.of()), obj("dnsAddresses", List.of("not-an-ip")),
                obj("dnsAddresses", List.of()), obj("resolverText", "")
            )) {
                JsonObject wire = Json.encode(first.boundary()).getAsJsonObject();
                change.entrySet().forEach(e -> wire.getAsJsonObject("capture").add(e.getKey(), e.getValue()));
                assertThrows(IllegalArgumentException.class, () -> Json.decode(wire, Network.Boundary.class));
            }
            JsonObject wire = Json.encode(first.boundary()).getAsJsonObject();
            wire.getAsJsonObject("request").addProperty("version", true);
            assertThrows(IllegalArgumentException.class, () -> Json.decode(wire, Network.Boundary.class));
            JsonObject different = Json.encode(first.boundary()).getAsJsonObject();
            different.getAsJsonObject("request").addProperty("compiler", "/different/backend");
            Network.Boundary changed = Json.decode(different, Network.Boundary.class);
            assertThrows(IllegalArgumentException.class, () -> changed.verify(policy.network(), config));
            assertThrows(IllegalArgumentException.class, () -> Json.decode(obj(
                "localDeniedPorts", List.of(3128), "hostResolvConf", "", "dnsEndpoints", List.of()
            ), Network.Boundary.class));
        }
    }
    
    @Test void test_effective_host_metadata_is_part_of_request_not_an_ignored_config() throws Exception {
        JsonObject input = input();
        input.remove("networkConfig");
        Policy inherited = Policy.read(input);
        try (var first = freeze(inherited, config)) {
            PlatformConfig changed = RuntimeFixture.platform(root.resolve("absent"), obj("network", obj("localDeniedPorts", List.of(9999))));
            assertThrows(IllegalArgumentException.class, () -> freeze(inherited, changed));
            assertEquals(1, captures);
            assertEquals(config.network(), first.boundary().request().settings());
        }
    }
    
}