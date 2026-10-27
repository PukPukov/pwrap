package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Resources.Binding;

/** Translate exact storage-neutral bindings to this platform's bwrap arguments. */
public final class Mounts {
    
    public static List<String> authority(FileAuthority.Pins pins) {
        List<String> arguments = new ArrayList<>();
        for (var b : pins.bindings()) {
            arguments.addAll(List.of(b.grant().writable() ? "--bind-fd" : "--ro-bind-fd", "" + b.fd().n, b.grant().destination()));
        }
        return arguments;
    }
    
    public static List<String> resources(Map<String, String> files, Collection<Binding> links, Collection<String> mounted) throws IOException {
        List<Binding> bindings = Resources.bindings(files, mounted); List<String> arguments = new ArrayList<>(), directories = new ArrayList<>(mounted);
        for (Binding b : bindings) {
            arguments.addAll(List.of("--ro-bind", b.source(), b.destination()));
            if (Files.isDirectory(Path.of(b.source()))) directories.add(b.destination());
        }
        for (Binding b : links.stream().sorted().toList()) {
            if (directories.stream().noneMatch(root -> Fs.under(b.destination(), root))) {
                arguments.addAll(List.of("--symlink", b.source(), b.destination()));
            }
        }
        return arguments;
    }
    
}