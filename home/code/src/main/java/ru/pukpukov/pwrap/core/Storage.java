package ru.pukpukov.pwrap.core;

import module java.base;
import ru.pukpukov.pwrap.core.Resources.Binding;

/** Storage resolves code/data authority; the platform only applies this projection. */
public interface Storage {
    
    /** Provenance of selected resource targets, not application-specific grants. */
    interface Provenance {
        boolean immutable(String path);
        boolean boundary(String path);
    }
    
    record Inputs(List<String> runtimeRoots, List<Binding> runtimeFiles) {
        public Inputs { runtimeRoots = List.copyOf(runtimeRoots); runtimeFiles = List.copyOf(runtimeFiles); }
    
        public static Inputs empty() { return new Inputs(List.of(), List.of()); }
    }
    
    record Runtime(List<String> paths, List<Binding> files, List<Binding> links, List<String> openerTargets, String path, List<String> directories) {
        public Runtime {
            paths = List.copyOf(paths); files = List.copyOf(files); links = List.copyOf(links);
            openerTargets = List.copyOf(openerTargets); directories = List.copyOf(directories);
        }
    }
    
    Provenance provenance();
    
    Runtime resolve(Policy policy, List<FileAuthority.Grant> grants, Inputs resources) throws Exception;
    
}