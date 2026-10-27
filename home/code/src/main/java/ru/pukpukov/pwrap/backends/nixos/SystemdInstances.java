package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Execution liveness and peer ownership from this platform's unified cgroups. */
public final class SystemdInstances implements Instances.Executions {
    
    public static final Instances HOST = create(Path.of("/run/pwrap-instances"), Path.of("/sys/fs/cgroup/system.slice"), Path.of("/proc"));
    private final Path cgroups, proc;
    public SystemdInstances(Path cgroups, Path proc) { this.cgroups = cgroups; this.proc = proc; }
    
    public static Instances create(Path records, Path cgroups, Path proc) { return new Instances(records, new SystemdInstances(cgroups, proc)); }
    
    public void validate(String identity) { require(identity.matches("pwrap-[a-zA-Z0-9-]+\\.service"), "Invalid instance unit"); }
    
    public boolean active(String identity) throws IOException {
        validate(identity); Path group = cgroups.resolve(identity), events = group.resolve("cgroup.events");
        if (!Files.exists(events)) { require(!Files.exists(group), "Missing pwrap cgroup.events for existing unit"); return false; }
        List<String> values = Files.readString(events).lines().map(String::strip)
            .filter(s -> s.matches("populated(?:\\s.*)?"))
            .map(s -> s.substring(9).strip())
            .toList();
        require(values.equals(List.of("0")) || values.equals(List.of("1")), "Invalid pwrap cgroup.events populated field");
        return values.equals(List.of("1"));
    }
    
    public boolean contains(String identity, long pid) throws IOException {
        validate(identity); require(pid > 0, "Invalid peer PID");
        Path file = proc.resolve(pid + "/cgroup"); if (!Files.exists(file)) return false;
        List<String> groups = Files.readString(file).lines().filter(s -> s.startsWith("0::")).map(s -> s.substring(3)).toList();
        require(groups.size() == 1, "D-Bus peer has no unique unified cgroup");
        return Fs.under(groups.getFirst(), "/system.slice/" + identity);
    }
    
}