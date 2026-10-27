package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.Endpoint.*;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Atomically replace only the nft table owned by this adapter. */
public final class ServiceEgress {
    
    private static final String OWNER = "pwrap native services";
    
    public record Service(Policy.Mode network, @Nullable List<Address> dns, Egress.Cgroup cgroup) {}
    
    public static void install(String nft, Egress.Name table, Map<Egress.Name, Service> policies) throws Exception {
        install(nft, table, policies, () -> Egress.Resolver.read(Path.of("/etc/resolv.conf")));
    }
    
    public static void install(
        String nft, Egress.Name table, Map<Egress.Name, Service> policies, Instances.Capture<Egress.Resolver> resolver
    ) throws Exception {
        Map<Egress.Name, Egress.Chain> entries = new LinkedHashMap<>(); Egress.Resolver inherited = null;
        for (var entry : policies.entrySet()) {
            Service policy = entry.getValue();
            require(policy.network == Policy.Mode.NONE || policy.network == Policy.Mode.INTERNET, "Invalid native service egress policy");
            List<Address> addresses = policy.network == Policy.Mode.NONE ? List.of() : policy.dns;
            if (addresses == null) { if (inherited == null) inherited = resolver.capture(); addresses = inherited.addresses(); }
            entries.put(entry.getKey(), new Egress.Chain(
                policy.cgroup,
                Egress.expressions(new Policy.Routes(policy.network, false, false), addresses, List.of(), null, List.of(Port.DNS))
            ));
        }
        String rules = Egress.table(table, entries, -175, OWNER);
        var old = Commands.result(List.of(nft, "--json", "list", "table", "inet", table.value()), System.getenv(), true);
        if (old.code() == 0) {
            List<Table> tables = new ArrayList<>();
            for (Entry entry : decode(JsonParser.parseString(old.output()), NftDocument.class).nftables) if (entry.table != null) tables.add(entry.table);
            require(tables.size() == 1 && OWNER.equals(tables.getFirst().comment), "Refusing to replace an unknown nft table: " + table);
            rules = "delete table inet " + table + "\n" + rules;
        }
        Commands.run(List.of(nft, "--file", "-"), rules, true);
    }
    
    @Json.Extensible private record Table(@Nullable String comment) {}
    @Json.Extensible private record Entry(@Nullable Table table) {}
    private record NftDocument(List<Entry> nftables) {}
    private record Document(Egress.Name table, Map<Egress.Name, Service> policies) {}
    
    public static void main(Path document, String nft) throws Exception {
        Document input = Json.read(document, Document.class); install(nft, input.table, input.policies);
    }
    
}