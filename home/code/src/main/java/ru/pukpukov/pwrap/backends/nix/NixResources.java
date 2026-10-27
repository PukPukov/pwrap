package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Resources.*;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import static ru.pukpukov.pwrap.core.Json.*;

/** Nix-store provenance and immutable-data authority; no execution/mount flags. */
public final class NixResources {
    
    public static @Nullable Resource resolve(String path) throws Exception { return resolve(path, "/nix/store", true); }
    
    public static @Nullable Resource resolve(String input, String store, boolean outside) throws Exception {
        return Resources.resolve(input, path -> path.startsWith(store + "/"), outside);
    }
    
    public static @Nullable Resource resolve(String input, String store, boolean outside, Resources.Access access) throws Exception {
        return Resources.resolve(input, path -> path.startsWith(store + "/"), outside, access);
    }
    
    public static Dependencies dependencies(List<Resource> resources, String store, @Nullable Link link, @Nullable Inspect inspect) throws Exception {
        return dependencies(resources, store, link, inspect, new HashSet<>());
    }
    
    public static Dependencies dependencies(
        List<Resource> resources, String store, @Nullable Link link, @Nullable Inspect inspect, Set<String> visited
    ) throws Exception {
        return Resources.dependencies(resources, path -> path.startsWith(store + "/"), link, inspect, visited);
    }
    
    public static List<Resource> canonical(List<Grant> grants, String store) throws Exception {
        List<Resource> result = new ArrayList<>();
        for (Grant grant : grants) if (!grant.writable() && grant.source().startsWith(store + "/") && Fs.real(grant.source()).equals(grant.source())) {
            Resource r = resolve(grant.source(), store, true);
            require(r != null, "Resource is not immutable store data: " + grant.source());
            result.add(new Resource(r.source(), grant.destination(), r.aliases()));
        }
        return result;
    }
    
    public static List<Resource> immutable(List<String> aliases, List<Grant> grants, String store) throws Exception {
        List<Resource> result = new ArrayList<>();
        for (String alias : aliases) {
            Resource r = resolve(alias, store, true);
            require(r != null && r.source().startsWith(store + "/"), "Resource is not immutable store data: " + alias);
            String output = store + "/" + r.source().substring(store.length() + 1).split("/", 2)[0];
            require(!r.source().equals(output) || !Files.isDirectory(Path.of(r.source())), "Refusing a whole immutable output directory: " + alias);
            for (Grant grant : grants) if (grant.writable()) for (String selected : List.of(alias, r.source()))
                for (String path : List.of(grant.source(), grant.destination(), Fs.real(grant.source())))
                    require(!Fs.under(selected, path) && !Fs.under(path, selected), "Immutable resource overlaps writable data: " + alias);
            result.add(r);
        }
        return result;
    }
    
    public static Dependencies resolveData(List<String> aliases, List<Grant> grants, String store) throws Exception {
        var immutable = immutable(aliases, grants, store);
        var selected = canonical(grants, store);
        selected.addAll(immutable);
        Dependencies d = dependencies(selected, store, null, null);
        Set<Binding> files = new TreeSet<>(d.files());
        immutable.forEach(r -> files.add(new Binding(r.source(), r.destination())));
        return new Dependencies(files, d.links());
    }
    
}