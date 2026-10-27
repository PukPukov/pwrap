package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.*;

class UriHandlersTest {
    
    @Test void test_custom_scheme_is_an_explicit_portal_capability() {
        Policy.read(obj("command", List.of("app"), "providers", obj("uri", obj("enable", true, "schemes", List.of("tg")))));
        for (JsonObject update : List.of(
            obj("providers", obj("uri", obj("schemes", "tg"))), obj("providers", obj("uri", obj("schemes", List.of(1)))),
            obj("providers", obj("uris", "direct")), obj("openUris", List.of("http")),
            obj("providers", obj("uri", obj("schemes", List.of("file")))), obj("providers", obj("uri", obj("schemes", List.of("TG")))),
            obj("providers", obj("uri", obj("enable", true, "schemes", List.of("tg"))), "network", "local")
        )) {
            JsonObject p = obj("command", List.of("app"));
            update.entrySet().forEach(e -> p.add(e.getKey(), e.getValue()));
            assertThrows(IllegalArgumentException.class, () -> Policy.read(p));
        }
    }
    
}