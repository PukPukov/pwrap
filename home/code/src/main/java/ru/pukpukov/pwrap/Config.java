package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Trusted composition decodes only the selected backend's descriptor. */
public final class Config {

    @Json.Extensible private record Selection(String storageBackend, String platformBackend) {}

    @Json.Extensible private record NixDescriptor(String nixStore, String baseClosure, String toolbox, String portalTools) {}

    public final String storageBackend;
    public final PlatformConfig platform;
    private final JsonObject raw;

    public Config(JsonObject descriptor) {
        raw = descriptor.deepCopy();
        Selection selected = decode(raw, Selection.class);
        storageBackend = selected.storageBackend;
        require(selected.platformBackend.equals("nixos"), "Unsupported platform backend: " + selected.platformBackend);
        platform = new PlatformConfig(raw);
    }

    public NixStorage.Tools nix() {
        require(storageBackend.equals("nix"), "Unsupported storage backend: " + storageBackend);
        NixDescriptor tools = decode(raw, NixDescriptor.class);
        return new NixStorage.Tools(tools.nixStore, tools.baseClosure, tools.toolbox, tools.portalTools + "/bin/xdg-open");
    }
}