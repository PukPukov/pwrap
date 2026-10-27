package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import com.google.gson.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Platform helper descriptors only; target storage is not selected or required here. */
public final class PlatformConfig {
    
    public enum Key {
        SETPRIV("setpriv"), BWRAP("bwrap"), PORTAL_TOOLS("portalTools"), SLIRP("slirp"), SLIRP_CLOSURE("slirpClosure"),
        DBUS_PROXY("dbusProxy"), DBUS_PROXY_CLOSURE("dbusProxyClosure"), WAYLAND_PROXY("waylandProxy"),
        WAYLAND_PROXY_CLOSURE("waylandProxyClosure"), WAYLAND_PROXY_CONFIG("waylandProxyConfig"), SYSTEMD_RUN("systemdRun"),
        BUSCTL("busctl"), IP("ip"), NFT("nft"), FC_LIST("fcList"), GSETTINGS("gsettings"),
        DCONF_MODULES("dconfModules"), DESKTOP_SCHEMAS("desktopSchemas"), SHELL("shell"), ENV("env"), TOOLBOX("toolbox"),
        CERT("cert"), FILTER("filter"), SCOPE("scope"), FONT_CONFIG("fontConfig"), LOCALE_ARCHIVE("localeArchive"), BASE_CLOSURE("baseClosure");
        
        public final String json;
        Key(String json) { this.json = json; }
        
    }
    
    public final String entry, hostConfigDir, identity;
    public final List<String> waylandInterfaces;
    private final Descriptor descriptor;
    private final JsonObject embedded;
    
    @Json.Extensible private record Descriptor(
        String entry, String hostConfigDir,
        String setpriv, String bwrap, String portalTools, String slirp, String slirpClosure,
        String dbusProxy, String dbusProxyClosure, String waylandProxy, String waylandProxyClosure, String waylandProxyConfig,
        String systemdRun, String busctl, String ip, String nft,
        String fcList, String gsettings, String dconfModules, String desktopSchemas, String shell,
        String env, String toolbox, String cert, String filter, String scope,
        String fontConfig, String localeArchive, String baseClosure,
        List<String> waylandInterfaces, @Nullable JsonObject metadata
    ) {}
    
    public PlatformConfig(JsonObject raw) {
        descriptor = decode(raw, Descriptor.class);
        entry = Objects.requireNonNull(descriptor.entry);
        hostConfigDir = Objects.requireNonNull(descriptor.hostConfigDir);
        waylandInterfaces = List.copyOf(descriptor.waylandInterfaces);
        require(waylandInterfaces.stream().allMatch(v -> !v.isEmpty() && v.indexOf(0) < 0), "Expected nonempty strings");
        embedded = descriptor.metadata == null ? new JsonObject() : descriptor.metadata;
        JsonObject authority = raw.deepCopy();
        authority.remove("metadata");
        identity = canonical(authority).toString();
        for (Key key : Key.values()) Objects.requireNonNull(path(key), "Missing runtime path: " + key);
    }
    
    public String path(Key key) { return switch (key) {
        case SETPRIV -> descriptor.setpriv;
        case BWRAP -> descriptor.bwrap;
        case PORTAL_TOOLS -> descriptor.portalTools;
        case SLIRP -> descriptor.slirp;
        case SLIRP_CLOSURE -> descriptor.slirpClosure;
        case DBUS_PROXY -> descriptor.dbusProxy;
        case DBUS_PROXY_CLOSURE -> descriptor.dbusProxyClosure;
        case WAYLAND_PROXY -> descriptor.waylandProxy;
        case WAYLAND_PROXY_CLOSURE -> descriptor.waylandProxyClosure;
        case WAYLAND_PROXY_CONFIG -> descriptor.waylandProxyConfig;
        case SYSTEMD_RUN -> descriptor.systemdRun;
        case BUSCTL -> descriptor.busctl;
        case IP -> descriptor.ip;
        case NFT -> descriptor.nft;
        case FC_LIST -> descriptor.fcList;
        case GSETTINGS -> descriptor.gsettings;
        case DCONF_MODULES -> descriptor.dconfModules;
        case DESKTOP_SCHEMAS -> descriptor.desktopSchemas;
        case SHELL -> descriptor.shell;
        case ENV -> descriptor.env;
        case TOOLBOX -> descriptor.toolbox;
        case CERT -> descriptor.cert;
        case FILTER -> descriptor.filter;
        case SCOPE -> descriptor.scope;
        case FONT_CONFIG -> descriptor.fontConfig;
        case LOCALE_ARCHIVE -> descriptor.localeArchive;
        case BASE_CLOSURE -> descriptor.baseClosure;
    }; }
    
    public JsonElement metadata(String name) throws IOException {
        if (embedded.has(name)) return embedded.get(name).deepCopy();
        Path file = Path.of(hostConfigDir, name + ".json");
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file)) : JsonNull.INSTANCE;
    }
    
    public Policy.NetworkSettings network() throws IOException {
        JsonElement value = metadata("network");
        return Policy.NetworkSettings.read(value.isJsonNull() ? new JsonObject() : value);
    }
    
}