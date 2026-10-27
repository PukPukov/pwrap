package ru.pukpukov.pwrap.core;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.*;

/** Trusted per-operation policy; the desktop owns handlers and UI. */
public record OpenActions(OpenFile openFile, Uri uri) {
    
    public record OpenFile(boolean enable, boolean ask) {}
    
    public record Uri(boolean enable, boolean ask, List<String> schemes) {
        public Uri {
            require(
                schemes.stream().allMatch(s -> s.equals("http") || s.equals("https") || Policy.customScheme(s)),
                "External URI schemes must be http/https or custom lowercase schemes, never file"
            );
            schemes = List.copyOf(new TreeSet<>(schemes));
            require(!enable || !schemes.isEmpty(), "Enabled URI opening needs at least one permitted scheme");
        }
    }
    
    public boolean enabled() { return openFile.enable || uri.enable; }
    
    public List<String> schemes() {
        Set<String> schemes = new TreeSet<>(uri.enable ? uri.schemes : List.of());
        if (openFile.enable) schemes.add("file");
        return List.copyOf(schemes);
    }
    
    @Json.Input static final class FileInput { Boolean enable = false, ask = true; }
    
    @Json.Input static final class UriInput {
        Boolean enable = false, ask = false;
        List<String> schemes = List.of("http", "https", "tg");
    }
    
}