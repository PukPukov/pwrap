package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import ru.pukpukov.pwrap.core.Resources.Binding;
import static ru.pukpukov.pwrap.core.Json.*;

public final class NixStorage implements Storage {
    
    public record Tools(String nixStore, String baseClosure, String toolbox, String opener) {}
    
    private final Tools config;
    
    public NixStorage(Tools config) { this.config = config; }
    
    public static final Provenance PROVENANCE = new Provenance() {
        public boolean immutable(String path) { return path.startsWith("/nix/store/"); }
        public boolean boundary(String path) { return Set.of("/nix", "/nix/store").contains(path) || path.matches("/nix/store/[^/]+"); }
    };
    
    public Provenance provenance() { return PROVENANCE; }
    
    public Runtime resolve(Policy policy, List<Grant> grants, Inputs resources) throws Exception {
        return resolve(policy, grants, resources, config);
    }
    
    public static @Nullable String root(String path) throws IOException {
        String real = Fs.real(path);
        if (!real.startsWith("/nix/store/")) return null;
        return "/nix/store/" + real.substring(11).split("/", 2)[0];
    }
    
    public static Set<String> closure(Collection<String> paths, Tools config) throws Exception {
        return closure(paths, config, Commands::output);
    }
    
    private static Set<String> closure(Collection<String> paths, Tools config, Commands.Query query) throws Exception {
        Set<String> roots = new TreeSet<>();
        for (String path : paths) {
            String r = root(path);
            if (r != null) roots.add(r);
        }
        return roots.isEmpty() ? new TreeSet<>() : new TreeSet<>(
            query.output(Commands.join(List.of(config.nixStore(), "--query", "--requisites"), new ArrayList<>(roots))).lines().toList()
        );
    }
    
    public static Set<String> readClosure(String path) throws IOException {
        Set<String> members = new TreeSet<>(Files.readAllLines(Path.of(path)));
        for (String member : members) require(member.matches("/nix/store/[a-z0-9]{32}-[^/\\s]+"), "Invalid Nix closure member: " + member);
        return members;
    }
    
    @FunctionalInterface interface Leaf { @Nullable String resolve(String path) throws IOException; }
    
    static List<String> openerTargets(boolean granted, Set<String> paths, String helper, Leaf resolve) throws IOException {
        if (!granted) return List.of();
        Set<String> targets = new TreeSet<>();
        for (String path : paths) {
            String target = resolve.resolve(path + "/bin/xdg-open");
            if (target != null) targets.add(target);
        }
        targets.remove(Fs.real(helper));
        for (String target : targets) {
            String output = root(target);
            require(output != null && paths.contains(output), "Web handler alias leaves the selected runtime closure");
        }
        return new ArrayList<>(targets);
    }
    
    public static Runtime resolve(Policy policy, List<Grant> grants, Inputs resources, Tools config) throws Exception {
        return resolve(policy, grants, resources, config, Commands::output);
    }
    
    public static Runtime resolve(Policy policy, List<Grant> grants, Inputs resources, Tools config, Commands.Query query) throws Exception {
        String executable = policy.command().getFirst();
        Policy.Invocation invocation = policy.invocation();
        require(root(executable) != null, "Nix backend requires a Nix-packaged executable: " + executable);
        for (String path : invocation.runtime()) require(root(path) != null, "Nix backend requires a Nix-packaged runtime: " + path);
        for (String path : resources.runtimeRoots()) require(root(path) != null, "Nix backend requires Nix-packaged resource code: " + path);
        Set<String> roots = new TreeSet<>(invocation.runtime());
        roots.add(executable);
        roots.addAll(resources.runtimeRoots());
        Set<Binding> extra = new TreeSet<>(resources.runtimeFiles());
        var data = NixResources.resolveData(policy.files().immutableData(), grants, "/nix/store");
        extra.addAll(data.files());
        Set<String> paths = readClosure(config.baseClosure());
        if (invocation.closure() != null) paths.addAll(readClosure(invocation.closure()));
        Set<String> missing = new TreeSet<>();
        for (String path : roots) if (!paths.contains(required(root(path), "Runtime path lost its packaged root: " + path))) missing.add(path);
        if (!missing.isEmpty()) paths.addAll(closure(missing, config, query));
        for (String path : paths) {
            require(path.matches("/nix/store/[a-z0-9]{32}-[^/]+"), "Invalid Nix closure member: " + path);
        }
        List<String> targets = openerTargets(
            policy.integration().desktop() || policy.integration().opens().enabled(), paths, config.opener(),
            path -> Files.isRegularFile(Path.of(path)) ? Fs.real(path) : null
        );
        List<String> bins = new ArrayList<>();
        for (String path : invocation.runtime()) bins.add(Fs.parent(Path.of(path)).toString());
        bins.add(config.toolbox() + "/bin");
        return new Runtime(
            new ArrayList<>(paths), new ArrayList<>(extra), new ArrayList<>(data.links()), new ArrayList<>(targets),
            invocation.path() != null ? invocation.path() : String.join(":", bins), List.of("/nix/store")
        );
    }
    
}