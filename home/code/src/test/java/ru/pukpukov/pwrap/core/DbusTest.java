package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.*;

/** Same cases as test_dbus.py, against the Java implementation rather than an oracle. */
class DbusTest {
    
    private static JsonObject operation() {
        return obj(
            "destination", "org.example.Service", "interface", "org.example.Interface",
            "path", "/org/example/Object", "members", List.of("Read", "Write")
        );
    }
    
    @Test void test_exact_operations_compile_once_without_granting_talk() {
        JsonObject broadcast = operation();
        broadcast.add("members", Json.encode(List.of("Changed")));
        Policy policy = Policy.read(obj("command", List.of("app"), "dbusCalls", List.of(operation()), "dbusBroadcasts", List.of(broadcast)));
        assertTrue(policy.integration().dbus().enabled());
        assertEquals(List.of(
            "--call=org.example.Service=org.example.Interface.Read@/org/example/Object",
            "--call=org.example.Service=org.example.Interface.Write@/org/example/Object",
            "--broadcast=org.example.Service=org.example.Interface.Changed@/org/example/Object"
        ), policy.integration().dbus().arguments());
    }
    
    @Test void test_local_only_cannot_gain_bus_via_new_operations() {
        for (String field : List.of("dbusCalls", "dbusBroadcasts"))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                "command", List.of("app"), "network", "local", field, List.of(operation())
            )));
    }
    
    @Test void test_operations_are_closed_exact_records_not_proxy_rule_fragments() {
        List<JsonElement> invalid = new ArrayList<>(List.of(
            JsonNull.INSTANCE, new JsonPrimitive(true), new JsonPrimitive("org.example.Service"), obj(), obj("typo", true)
        ));
        for (JsonObject update : List.of(
            obj("destination", "org.example.*"), obj("destination", ":1.42"),
            obj("interface", "org.example.*"), obj("interface", "Read"),
            obj("path", "/org/example/*"), obj("path", "/org//Object"), obj("path", "/org/Object@/anywhere"),
            obj("members", List.of()), obj("members", "*"), obj("members", List.of("*")),
            obj("members", List.of("Read@/else")), obj("members", List.of("Read\n")), obj("members", List.of(42))
        )) {
            JsonObject value = operation();
            update.entrySet().forEach(e -> value.add(e.getKey(), e.getValue()));
            invalid.add(value);
        }
        for (String key : operation().keySet()) {
            JsonObject value = operation();
            value.remove(key);
            invalid.add(value);
        }
        for (JsonElement value : invalid) {
            for (String field : List.of("dbusCalls", "dbusBroadcasts")) {
                assertThrows(IllegalArgumentException.class, () -> Policy.read(obj(
                    "command", List.of("app"), field, List.of(value)
                )), value.toString());
            }
        }
        for (JsonElement value : List.of(JsonNull.INSTANCE, obj(), new JsonPrimitive(true), new JsonPrimitive("raw-rule")))
            assertThrows(IllegalArgumentException.class, () -> Policy.read(obj("command", List.of("app"), "dbusCalls", value)));
    }
    
    @Test void test_explicit_full_peer_authority_is_still_explicit() {
        Dbus policy = Dbus.read(obj("dbusTalk", List.of("org.example.Peer.*"), "dbusOwn", List.of("org.example.Peer.App")));
        assertEquals(List.of("--talk=org.example.Peer.*", "--own=org.example.Peer.App"), policy.arguments());
        assertFalse(Dbus.read(obj()).enabled());
    }
    
}