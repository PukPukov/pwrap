package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.Endpoint.*;
import ru.pukpukov.pwrap.core.Policy.Routes;
import static ru.pukpukov.pwrap.core.Json.*;

public final class Egress {
    
    public static final List<String> LOOPBACK = List.of("127.0.0.0/8", "::1/128");
    
    public static final List<String> LAN = List.of(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10"
    );
    
    public record Resolver(String text, List<Address> addresses) {
        public static Resolver read(Path path) throws IOException {
            String text = Files.readString(path);
            Set<Address> addresses = new LinkedHashSet<>();
            for (String line : text.lines().toList()) {
                String clean = line.split("[#;]", 2)[0].strip();
                if (clean.isEmpty()) continue;
                String[] words = clean.split("\\s+");
                if (!words[0].equals("nameserver")) continue;
                require(words.length == 2, "Unsupported nameserver in " + path + ": " + line);
                addresses.add(new Address(words[1]));
            }
            require(!addresses.isEmpty(), "No literal nameserver configured in " + path);
            return new Resolver(text, List.copyOf(addresses));
        }
    }
    
    public static String joined(Collection<?> values) { return values.stream().map(String::valueOf).collect(Collectors.joining(", ")); }
    
    public static List<String> localPrefixes(Routes p) {
        List<String> values = new ArrayList<>();
        if (p.allowLocalhost()) values.addAll(LOOPBACK);
        if (p.allowLAN()) values.addAll(LAN);
        return values;
    }
    
    public static List<String> expressions(Routes p, List<Address> dns, List<Port> denied, @Nullable Port canary, List<Port> dnsPorts) {
        String mode = p.mode().name().toLowerCase(Locale.ROOT);
        require(Set.of("none", "internet", "lan", "local").contains(mode), "Unsupported filtered network mode: " + mode);
        List<Address> addresses = List.copyOf(dns);
        require(!Set.of("none", "local").contains(mode) || addresses.isEmpty(), "Invalid resolver exception");
        List<String> rules = new ArrayList<>(List.of("ct state { invalid, untracked } counter drop"));
        if (!mode.equals("local")) rules.add("ct direction reply return");
        if (canary != null) rules.add("ip daddr 127.0.0.1 tcp dport " + canary + " counter reject");
        if (mode.equals("none")) {
            rules.add("counter reject");
            return rules;
        }
        String scope = mode.equals("local") ? "" : "fib daddr type { local, anycast } ";
        if (!denied.isEmpty()) rules.add(scope + "meta l4proto { tcp, udp } th dport { " + joined(denied) + " } counter reject");
        for (Address address : addresses) {
            rules.add((address.ipv6() ? "ip6" : "ip") + " daddr " + address + " meta l4proto { tcp, udp } th dport 53 return");
        }
        rules.add("meta l4proto { tcp, udp } th dport { " + joined(dnsPorts) + " } counter reject");
        if (p.allowLocalhost()) for (String family : List.of("ip", "ip6")) {
            List<String> prefixes = LOOPBACK.stream().filter(v -> v.contains(":") == family.equals("ip6")).toList();
            rules.add("fib daddr type { local, anycast } " + family + " daddr { " + joined(prefixes) + " } return");
        }
        rules.add("fib daddr type { local, anycast } counter reject");
        if (mode.equals("local")) {
            for (String family : List.of("ip", "ip6")) {
                List<String> prefixes = localPrefixes(p).stream().filter(v -> v.contains(":") == family.equals("ip6")).toList();
                if (!prefixes.isEmpty()) rules.add(family + " daddr { " + joined(prefixes) + " } return");
            }
            rules.add("counter reject");
            return rules;
        }
        if (p.allowLocalhost()) rules.addAll(List.of("ip daddr 127.0.0.0/8 return", "ip6 daddr ::1 return"));
        rules.addAll(List.of("ip daddr 127.0.0.0/8 counter reject", "ip6 daddr ::1 counter reject"));
        if (mode.equals("internet")) rules.addAll(List.of(
            "fib daddr type { broadcast, multicast } counter reject",
            "ip daddr { 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 169.254.0.0/16, 172.16.0.0/12, " +
                "192.168.0.0/16, 224.0.0.0/4, 240.0.0.0/4 } counter reject",
            "ip6 daddr { ::/128, fe80::/10, fc00::/7, ff00::/8 } counter reject"
        ));
        return rules;
    }
    
    @JsonAdapter(value = NameCodec.class, nullSafe = false)
    public record Name(String value) {
        public Name { require(value.matches("[A-Za-z_][A-Za-z0-9_]*"), "Invalid nft identity: " + value); }
        
        public String toString() { return value; }
    }
    
    @JsonAdapter(value = CgroupCodec.class, nullSafe = false)
    public record Cgroup(String value) {
        public Cgroup {
            for (String part : value.split("/", -1)) {
                require(part.matches("[A-Za-z0-9_@.-]+") && !Set.of(".", "..").contains(part), "Invalid cgroup egress identity");
            }
        }
        
        public int level() { return value.split("/").length; }
        
        public String toString() { return value; }
    }
    
    public static final class NameCodec extends TypeAdapter<Name> {
        public Name read(JsonReader input) throws IOException {
            require(input.peek() == JsonToken.STRING, "Expected nft identity string");
            return new Name(input.nextString());
        }
        
        public void write(JsonWriter output, Name value) throws IOException { output.value(value.value()); }
    }
    
    public static final class CgroupCodec extends TypeAdapter<Cgroup> {
        public Cgroup read(JsonReader input) throws IOException {
            require(input.peek() == JsonToken.STRING, "Expected cgroup identity string");
            return new Cgroup(input.nextString());
        }
        
        public void write(JsonWriter output, Cgroup value) throws IOException { output.value(value.value()); }
    }
    
    public record Chain(Cgroup group, List<String> rules) {
        public Chain {
            Objects.requireNonNull(group);
            rules = List.copyOf(rules);
        }
    }
    
    public static String table(Name table, Map<Name, Chain> entries, int priority, String comment) {
        List<String> rules = new ArrayList<>(List.of(
            "create table inet " + table + " { comment " + encode(comment).toString() + "; }",
            "add chain inet " + table + " output { type filter hook output priority " + priority + "; policy accept; }"
        ));
        entries.forEach((name, chain) -> {
            rules.add("add chain inet " + table + " " + name);
            rules.add("add rule inet " + table + " output socket cgroupv2 level " + chain.group.level() + " \"" + chain.group + "\" jump " + name);
            chain.rules.forEach(rule -> rules.add("add rule inet " + table + " " + name + " " + rule));
        });
        return String.join("\n", rules) + "\n";
    }
    
}