package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import static ru.pukpukov.pwrap.core.Json.*;

public record Session(String home, @Nullable String runtime) {
    
    public static Session from(Map<String, String> environment) {
        String home = environment.get("HOME"), runtime = environment.get("XDG_RUNTIME_DIR");
        require(home != null && home.startsWith("/") && home.indexOf(0) < 0, "Launch session requires an absolute HOME");
        require(runtime == null || runtime.startsWith("/") && runtime.indexOf(0) < 0, "Launch session runtime directory must be absolute");
        return new Session(home, runtime);
    }
    
    public record Environment(Map<String, String> values) {
        public Environment {
            Session.from(values);
            require(values.values().stream().allMatch(v -> v.indexOf(0) < 0), "Invalid captured session environment");
            values = Map.copyOf(values);
        }
    
        public Session session() { return Session.from(values); }
    }
    
    public static Environment capture(Collection<String> names) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null) result.put(name, value);
        }
        return new Environment(result);
    }
    
    public String bus() {
        require(runtime != null, "D-Bus operation requires a captured session runtime directory");
        return "unix:path=" + runtime + "/bus";
    }
}