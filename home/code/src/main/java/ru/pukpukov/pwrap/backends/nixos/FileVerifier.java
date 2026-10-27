package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.FileAuthority.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.Posix.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;

/** Execute authority checks/anchored mkdir in the selected capability-free helper. */
public final class FileVerifier {
    
    private static void withoutCapabilities(List<String> arguments, PlatformConfig config, List<Fd> fds) throws Exception {
        var command = Commands.join(List.of(
            config.path(SETPRIV), "--bounding-set=-all", "--inh-caps=-all", "--ambient-caps=-all",
            "--clear-groups", "--no-new-privs", config.entry, "_file-authority"
        ), arguments);
        try (Child child = spawn(command, Map.of("LANG", "C"), fds, 0, 1, 2)) { require(child.waitFor() == 0, "File authority verification failed"); }
    }
    
    public static void preflight(List<Grant> grants, PlatformConfig config, List<Fd> fds) throws Exception {
        var args = Commands.args(fds.isEmpty() ? "_preflight" : "_preflight-pins", encode(grants.stream().map(Grant::json).toList()).toString());
        if (!fds.isEmpty()) args.add(encode(fds.stream().map(fd -> fd.n).toList()).toString());
        withoutCapabilities(args, config, fds);
    }
    
    public static Mkdir mkdir(PlatformConfig config) {
        return (parent, name) -> {
            Fd borrowed = new Fd(parent); // borrowed; the core owns this directory pin
            withoutCapabilities(
                Commands.args("_mkdir", encode(new MkdirRequest(FileAuthority.physical(borrowed), parent, name)).toString()),
                config, List.of(borrowed)
            );
        };
    }
    
}