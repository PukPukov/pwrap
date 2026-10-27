package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.Endpoint.*;

class JsonBoundaryTest {
    
    private static JsonElement json(String value) { return JsonParser.parseString(value); }
    
    @Test void gson_binds_nested_domain_values_without_a_primitive_intermediate_model() {
        var settings = Policy.NetworkSettings.read(json("{\"localDeniedPorts\":[53,2080]}"));
        assertEquals(List.of(new Port(53), new Port(2080)), settings.localDeniedPorts());
        assertEquals(new JsonPrimitive(53), Json.encode(settings.localDeniedPorts().getFirst()));
        var addresses = Json.decode(json("[\"2001:0DB8::53\",\"127.0.0.53\"]"), new TypeToken<List<Address>>() {});
        assertEquals(List.of(new Address("2001:db8::53"), new Address("127.0.0.53")), addresses);
        assertEquals(json("[\"2001:db8::53\",\"127.0.0.53\"]"), Json.encode(addresses));
    }
    
    @Test void required_record_fields_and_container_members_are_checked_before_binding() {
        for (String input : List.of("null", "{}", "{\"source\":\"/selected\"}", "{\"source\":null,\"destination\":\"/selected\",\"writable\":false}"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(json(input), FileAuthority.Grant.class), input);
        for (String input : List.of("null", "{}", "[null]", "[\"ok\",null]"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(json(input), new TypeToken<List<String>>() {}), input);
        for (String input : List.of("null", "[]", "{\"key\":null}"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(json(input), new TypeToken<Map<String, String>>() {}), input);
        assertEquals(List.of("selected"), Json.decode(json("[\"selected\"]"), new TypeToken<List<String>>() {}));
    }
    
    @Test void invalid_ports_cannot_be_constructed_or_deserialized() {
        for (int value : new int[]{-1, 0, 65536, Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> new Port(value));
        for (String value : List.of("0", "65536", "9999999999999999999", "53.0", "5.3e1", "\"53\"", "true", "null", "{}"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(json(value), Port.class), value);
    }
    
    @Test void missing_defaults_do_not_make_explicit_nulls_or_scalar_coercions_legal() {
        Policy.read(json("{\"command\":[\"app\"]}").getAsJsonObject());
        for (String field : List.of(
            "desktop", "gpu", "tasksMax", "memoryMax", "networkConfig", "providers", "ipc", "readOnly", "environment", "activationArguments"
        )) {
            JsonObject input = json("{\"command\":[\"app\"]}").getAsJsonObject();
            input.add(field, JsonNull.INSTANCE);
            assertThrows(RuntimeException.class, () -> Policy.read(input), field);
        }
        for (String extra : List.of(
            "\"desktop\":\"false\"", "\"environment\":{\"OK\":1}", "\"network\":\"NONE\"",
            "\"providers\":{\"audio\":\"automatic\"}", "\"providers\":{\"typo\":\"nixos\"}"
        ))
            assertThrows(RuntimeException.class, () -> Policy.read(json("{\"command\":[\"app\"]," + extra + "}").getAsJsonObject()), extra);
    }
    
}