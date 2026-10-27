package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import static ru.pukpukov.pwrap.core.Json.*;

public record Dbus(List<String> talk, List<String> own, List<Operation> calls, List<Operation> broadcasts) {
    
    public static final String NAME = "[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+";
    public static final String INTERFACE = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+";
    
    public Dbus {
        require(
            calls != null && broadcasts != null && calls.stream().allMatch(Objects::nonNull) && broadcasts.stream().allMatch(Objects::nonNull),
        "D-Bus operations must be exact records");
        talk = Policy.nonempty(talk);
        own = Policy.nonempty(own);
        calls = List.copyOf(calls);
        broadcasts = List.copyOf(broadcasts);
    }
    
    public record Operation(String destination, @SerializedName("interface") String interfaceName, String path, List<String> members) {
        public Operation {
            require(destination.length() <= 255 && destination.matches(NAME), "D-Bus operation destination must be an exact protocol identifier");
            require(interfaceName.length() <= 255 && interfaceName.matches(INTERFACE), "D-Bus operation interface must be an exact protocol identifier");
            require(path.matches("/(?:[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)*)?"), "D-Bus operation path must be an exact protocol identifier");
            require(
                !members.isEmpty() && members.stream().allMatch(v -> v.length() <= 255 && v.matches("[A-Za-z_][A-Za-z0-9_]*")),
            "D-Bus members must be a nonempty list of exact member names");
            members = List.copyOf(members);
        }
        
        List<String> arguments(String option) {
            return members.stream().map(m -> "--" + option + "=" + destination + "=" + interfaceName + "." + m + "@" + path).toList();
        }
    }
    
    public static Dbus read(JsonObject document) { return decode(document, Policy.Manifest.class).dbus(); }
    
    public boolean enabled() { return !talk.isEmpty() || !own.isEmpty() || !calls.isEmpty() || !broadcasts.isEmpty(); }
    
    public List<String> arguments() {
        List<String> args = new ArrayList<>();
        talk.forEach(v -> args.add("--talk=" + v));
        own.forEach(v -> args.add("--own=" + v));
        calls.forEach(v -> args.addAll(v.arguments("call")));
        broadcasts.forEach(v -> args.addAll(v.arguments("broadcast")));
        return args;
    }
    
}