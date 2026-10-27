package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.pukpukov.pwrap.backends.nixos.Egress;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.*;

/** Harvested Python regressions; intentional post-migration policy changes are annotated in the fixture. */
class ReferenceParityTest {
    
    private static List<Endpoint.Port> ports(JsonElement raw) {
        JsonObject input = new JsonObject();
        input.add("localDeniedPorts", raw);
        return Policy.NetworkSettings.read(input).localDeniedPorts();
    }
    
    private static JsonElement evaluate(String operation, JsonObject args) {
        return switch (operation) {
            case "policy" -> {
                Policy.read(object(args.get("policy")));
                yield JsonNull.INSTANCE;
            }
            case "dbus-operations" -> {
                JsonObject o = new JsonObject();
                o.add("dbusCalls", args.get("operations"));
                Dbus.read(o);
                yield JsonNull.INSTANCE;
            }
            case "dbus-arguments" -> encode(Dbus.read(object(args.get("policy"))).arguments());
            case "egress" -> {
                JsonObject p = object(args.get("policy"));
                Policy.Mode mode = Policy.Mode.valueOf(string(p, "network", "none").toUpperCase(Locale.ROOT));
                Policy.Routes routes = new Policy.Routes(mode, bool(p, "allowLocalhost", false), bool(p, "allowLAN", false));
                JsonObject dns = new JsonObject();
                dns.add("addresses", args.get("dns_addresses"));
                Endpoint.Port canary = args.get("canary").isJsonNull() ? null : decode(args.get("canary"), Endpoint.Port.class);
                yield encode(Egress.expressions(
                    routes,
                    decode(args.get("dns_addresses"), new TypeToken<List<Endpoint.Address>>() {}),
                    ports(args.get("denied_ports")), canary, ports(args.get("dns_ports"))
                ));
            }
            case "table" -> {
                Map<Egress.Name, Egress.Chain> entries = new LinkedHashMap<>();
                object(args.get("entries")).entrySet().forEach(e -> {
                    JsonArray pair = e.getValue().getAsJsonArray();
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("rules", pair.get(1));
                    entries.put(new Egress.Name(e.getKey()), new Egress.Chain(
                        new Egress.Cgroup(string(pair.get(0))), list(wrapper, "rules", TestJson::string)
                    ));
                });
                yield new JsonPrimitive(Egress.table(
                    new Egress.Name(string(args.get("table"))), entries,
                    Math.toIntExact(integer(args, "priority", -174)), string(args.get("comment"))
                ));
            }
            default -> throw new IllegalArgumentException("Unknown oracle operation: " + operation);
        };
    }
    
    @TestFactory Stream<DynamicTest> python_regression_calls_with_explicit_policy_changes() throws IOException {
        JsonArray cases = JsonParser.parseString(Files.readString(Path.of("home/code/src/test/java-reference/pure-cases.json"))).getAsJsonArray();
        return IntStream.range(0, cases.size()).mapToObj(index -> {
            JsonObject c = cases.get(index).getAsJsonObject();
            String name = index + " " + c.get("operation").getAsString();
            return DynamicTest.dynamicTest(name, () -> {
                String operation = c.get("operation").getAsString();
                JsonObject args = c.getAsJsonObject("arguments");
                if (c.get("ok").getAsBoolean()) assertEquals(c.get("result"), evaluate(operation, args), args.toString());
                else assertThrows(RuntimeException.class, () -> evaluate(operation, args), args.toString());
            });
        });
    }
    
}