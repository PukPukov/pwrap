package ru.pukpukov.pwrap;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test void platform_descriptor_does_not_require_storage_or_selection_fields() {
        var raw = descriptor(Path.of("/absent"), obj());
        for (String key : List.of("nixStore", "storageBackend", "platformBackend")) raw.remove(key);
        assertEquals("/tools/bwrap", new PlatformConfig(raw).path(PlatformConfig.Key.BWRAP));
    }

    @Test void selected_nix_descriptor_is_required_without_fallback() {
        var raw = descriptor(Path.of("/absent"), obj()); raw.remove("nixStore");
        Config config = new Config(raw);
        assertThrows(IllegalArgumentException.class, config::nix);
        raw.addProperty("storageBackend", "other");
        assertThrows(IllegalArgumentException.class, () -> new Config(raw).nix());
    }

    @Test void unknown_platform_cannot_select_the_nixos_implementation() {
        var raw = descriptor(Path.of("/absent"), obj()); raw.addProperty("platformBackend", "other");
        assertThrows(IllegalArgumentException.class, () -> new Config(raw));
    }
    
}