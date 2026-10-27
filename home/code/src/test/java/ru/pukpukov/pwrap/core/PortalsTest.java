package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import ru.pukpukov.pwrap.backends.nixos.Portals;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

class PortalsTest {
    
    @Test void test_generic_integrations_compile_to_narrow_backend_methods_and_configured_asks() {
        Set<Policy.Authority> authorities = new HashSet<>();
        for (boolean uri : List.of(false, true)) for (boolean file : List.of(false, true))
            for (boolean fileAsk : List.of(false, true)) for (boolean uriAsk : List.of(false, true)) {
                Policy policy = Policy.read(obj(
                    "command", List.of("app"), "providers", obj(
                        "openFile", obj("enable", file, "ask", fileAsk),
                        "uri", obj("enable", uri, "ask", uriAsk, "schemes", List.of("https", "tg", "http"))
                    )
                ));
                var p = policy.integration();
                var opens = p.opens();
                var args = Portals.arguments(opens);
                authorities.add(policy.authority());
                assertEquals(uri || file, p.bus());
                assertEquals(file, opens.schemes().contains("file"));
                assertEquals(uri, opens.schemes().containsAll(List.of("http", "https", "tg")));
                assertEquals(uri, args.contains("--call=" + Portals.NAME + "=org.freedesktop.portal.OpenURI.OpenURI@" + Portals.PATH));
                assertEquals(file, args.contains("--call=" + Portals.NAME + "=org.freedesktop.portal.OpenURI.OpenFile@" + Portals.PATH));
                assertEquals(opens.enabled(), args.contains(
                    "--call=" + Portals.NAME + "=org.freedesktop.portal.Request.Close@" + Portals.PATH + "/request/*"
                ));
                assertTrue(args.contains("--pwrap-portal-file-ask=" + fileAsk));
                assertTrue(args.contains("--pwrap-portal-uri-ask=" + uriAsk));
                assertEquals("--pwrap-portal-open=" + String.join(",", opens.schemes()), args.getFirst());
                assertTrue(args.stream().noneMatch(s ->
                    s.startsWith("--talk=") || s.contains("OpenDirectory") || s.contains("FileChooser") || s.contains("ScreenCast")
                ));
            }
        assertEquals(16, authorities.size(), "Ask policy must be part of instance authority, not client environment");
    }
    
    @Test void test_defaults_are_dormant_and_legacy_raw_bus_does_not_disable_policy() {
        var p = Policy.read(obj("command", List.of("app"), "dbusTalk", List.of(Portals.NAME))).integration();
        assertTrue(p.bus());
        assertFalse(p.opens().enabled());
        assertEquals(new OpenActions.OpenFile(false, true), p.opens().openFile());
        assertEquals(new OpenActions.Uri(false, false, List.of("http", "https", "tg")), p.opens().uri());
        var enabled = Policy.read(obj(
            "command", List.of("app"), "providers", obj("uri", obj("enable", true), "openFile", obj("enable", true))
        )).integration().opens();
        assertEquals(List.of("http", "https", "tg"), enabled.uri().schemes());
        assertFalse(enabled.uri().ask());
        assertTrue(enabled.openFile().ask());
        assertEquals(List.of("--pwrap-portal-open=", "--pwrap-portal-file-ask=true", "--pwrap-portal-uri-ask=false"), Portals.arguments(p.opens()));
    }
    
    @Test void test_provider_schema_defaults_strict_types_and_normalized_scheme_authority() {
        for (JsonObject invalid : List.of(
            obj("providers", true), obj("providers", obj("typo", obj())),
            obj("providers", obj("openFile", obj("ask", "false"))), obj("providers", obj("uri", obj("ask", 1))),
            obj("providers", obj("uri", obj("enable", true, "schemes", List.of()))),
            obj("providers", obj("openFile", obj("ask", null))), obj("openWeb", true), obj("openFiles", true), obj("openUris", List.of("tg")),
            obj("providers", obj("web", obj("enable", true))), obj("integrations", obj()), obj("portals", obj())
        )) {
            invalid.add("command", Json.encode(List.of("app")));
            assertThrows(IllegalArgumentException.class, () -> Policy.read(invalid));
        }
        var a = Policy.read(obj("command", List.of("app"), "providers", obj("uri", obj("schemes", List.of("tg", "https", "tg")))));
        var b = Policy.read(obj("command", List.of("app"), "providers", obj("uri", obj("schemes", List.of("https", "tg")))));
        assertEquals(a.authority(), b.authority());
        assertThrows(UnsupportedOperationException.class, () -> a.integration().opens().uri().schemes().add("http"));
    }
    
}