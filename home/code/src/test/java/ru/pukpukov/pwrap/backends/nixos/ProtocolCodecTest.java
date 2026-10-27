package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import ru.pukpukov.pwrap.core.*;

class ProtocolCodecTest {
    
    @Test void backend_values_keep_scalar_wire_format_and_typed_map_keys() {
        JsonElement wire = JsonParser.parseString("{\"restricted\":{\"group\":\"system.slice/test.service\",\"rules\":[\"rule\"]}}");
        var value = Json.decode(wire, new TypeToken<Map<Egress.Name, Egress.Chain>>() {});
        assertEquals(Map.of(new Egress.Name("restricted"), new Egress.Chain(new Egress.Cgroup("system.slice/test.service"), List.of("rule"))), value);
        assertEquals(wire, Json.encode(value));
        assertEquals(new Egress.Name("pwrap_test"), Json.decode(new JsonPrimitive("pwrap_test"), Egress.Name.class));
        assertEquals(new JsonPrimitive("pwrap_test"), Json.encode(new Egress.Name("pwrap_test")));
    }
    
    @Test void backend_scalar_codecs_refuse_null_coercion_objects_and_invalid_identifiers() {
        for (String input : List.of("null", "42", "true", "{}", "[]", "\"\"", "\"../outside\"", "\"bad;name\"")) {
            JsonElement value = JsonParser.parseString(input);
            assertThrows(IllegalArgumentException.class, () -> Json.decode(value, Egress.Name.class));
            assertThrows(IllegalArgumentException.class, () -> Json.decode(value, Egress.Cgroup.class));
        }
        for (String input : List.of("[null]", "[42]", "[{}]"))
            assertThrows(IllegalArgumentException.class, () -> Json.decode(JsonParser.parseString(input), new TypeToken<List<Egress.Name>>() {}));
    }
    
}