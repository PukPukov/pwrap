package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static org.graalvm.nativeimage.c.type.CTypeConversion.*;

/** Startup checks in NM's actual unit policy; no host credentials or existing state are modified. */
public final class NetworkManagerAudit {
    
    private record Call(String destination, String path, String method, String signature, List<String> arguments) {}
    
    public static void run(String seccompLibrary, List<String> forbidden) throws Exception {
        Map<String, String> status = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            int i = line.indexOf(':');
            if (i >= 0) status.put(line.substring(0, i), line.substring(i + 1).strip());
        }
        long expected = (1L << 10) | (1L << 12) | (1L << 13);
        for (String field : List.of("CapEff", "CapPrm", "CapBnd", "CapAmb"))
            require(
                Long.parseUnsignedLong(required(status.get(field), "Missing capability field: " + field), 16) == expected,
            "Unexpected NM capability set: " + field);
        require("1".equals(status.get("NoNewPrivs")) && "2".equals(status.get("Seccomp")), "NM requires NNP/seccomp");
        for (String path : Commands.join(
            List.of(
                "/etc/shadow", "/run/dbus/system_bus_socket", "/run/systemd/private", "/run/docker.sock",
                "/nix/var/nix/daemon-socket/socket", "/proc/1/root/etc/shadow"
            ),
            forbidden
        )) require(!Files.exists(Path.of(path)), "Unexpected host grant: " + path);
        for (String path : List.of("/etc/NetworkManager/NetworkManager.conf", "/run/udev/data"))
            require(Files.exists(Path.of(path)), "Missing required grant: " + path);
        for (String directory : List.of("/etc/NetworkManager/system-connections", "/var/lib/NetworkManager", "/run/NetworkManager")) {
            Path path = Path.of(directory, ".pwrap-audit-" + UUID.randomUUID());
            try (var fd = Posix.open(path.toString(), Posix.O_CREAT() | Posix.O_EXCL() | Posix.O_RDWR())) {
                fd.write("sandbox write check\n".getBytes(StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(path);
            }
        }
        for (String directory : List.of("/etc/NetworkManager", "/etc", "/sys")) {
            Path path = Path.of(directory, ".pwrap-audit-" + UUID.randomUUID());
            try (var _ = Posix.open(path.toString(), Posix.O_CREAT() | Posix.O_EXCL() | Posix.O_RDWR())) {
                Files.delete(path);
                throw new IllegalStateException("Unexpected write access: " + directory);
            } catch (Posix.Failure denied) {
                require(Set.of(1, 13, 30).contains(denied.errno), "Unexpected file-write failure: " + denied.getMessage());
            }
        }
        syscalls(seccompLibrary);
        bus();
        System.out.println(
            "NM pwrap audit PASS: own state writable; host data/control, extra capabilities and unsafe syscalls denied; " +
        "bus method/unique-name filters enforced");
    }
    
    private static void syscalls(String library) throws IOException {
        try (var path = toCString(library); var symbol = toCString("seccomp_syscall_resolve_name")) {
            var handle = Posix.dlopen(path.get(), Posix.RTLD_NOW());
            require(handle.isNonNull(), "Cannot load libseccomp");
            try {
                var resolve = Posix.dlsym(handle, symbol.get());
                require(resolve.isNonNull(), "Missing libseccomp syscall resolver");
                Map<String, long[]> checks = new LinkedHashMap<>();
                checks.put("bpf", new long[]{-1, 0, 0, 0, 0, 0});
                checks.put("init_module", new long[6]);
                checks.put("finit_module", new long[]{-1, 0, 0, 0, 0, 0});
                checks.put("ptrace", new long[]{-1, -1, 0, 0, 0, 0});
                checks.put("process_vm_readv", new long[]{-1, 0, 0, 0, 0, 0});
                checks.put("setns", new long[]{-1, 0, 0, 0, 0, 0});
                checks.put("io_uring_setup", new long[6]);
                for (var check : checks.entrySet()) try (var name = toCString(check.getKey())) {
                    int number = resolve.resolve(name.get());
                    require(number != -1, "Unknown native syscall: " + check.getKey());
                    long[] a = check.getValue();
                    Posix.__errno_location().write(0);
                    long result = Posix.syscall(number, a[0], a[1], a[2], a[3], a[4], a[5]);
                    require(result == -1 && Posix.errno() == 1, "Unsafe syscall was not denied: " + check.getKey());
                }
            } finally {
                Posix.dlclose(handle);
            }
        }
    }
    
    private static void bus() throws IOException {
        String address = System.getenv().getOrDefault("DBUS_SYSTEM_BUS_ADDRESS", "unix:path=/run/dbus/system_bus_socket");
        try (var bus = new Bus(address)) {
            require(
                bus.call(
                    "org.freedesktop.login1", "/org/freedesktop/login1",
                    "org.freedesktop.DBus.Properties.GetAll", "s", List.of("org.freedesktop.login1.Manager")
                ).type() == 2,
            "Allowed login1 properties call failed");
            var owner = bus.call(
                "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus.GetNameOwner", "s", List.of("org.freedesktop.login1"));
            require(owner.type() == 2, "Cannot discover login1 owner");
            List<Call> denied = List.of(
                new Call("org.freedesktop.login1", "/org/freedesktop/login1", "org.freedesktop.login1.Manager.ListSessions", "", List.of()),
                new Call(owner.firstString(), "/org/freedesktop/login1", "org.freedesktop.login1.Manager.ListSessions", "", List.of()),
                new Call(
                    "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
                "org.freedesktop.systemd1.Manager.GetUnit", "s", List.of("NetworkManager.service")),
                new Call(
                    "org.freedesktop.NetworkManager.Dispatcher", "/org/freedesktop/nm_dispatcher",
                "org.freedesktop.DBus.Peer.Ping", "", List.of()),
            new Call("org.kde.kded6", "/kded", "org.freedesktop.DBus.Peer.Ping", "", List.of()));
            Set<String> expected = Set.of(
                "org.freedesktop.DBus.Error.AccessDenied", "org.freedesktop.DBus.Error.NameHasNoOwner",
            "org.freedesktop.DBus.Error.ServiceUnknown");
            for (Call call : denied) {
                var reply = bus.call(call.destination, call.path, call.method, call.signature, call.arguments);
                require(reply.type() == 3 && expected.contains(reply.error()), "D-Bus method filter failed for " + call.destination + ": " + reply.error());
            }
        }
    }
    
}