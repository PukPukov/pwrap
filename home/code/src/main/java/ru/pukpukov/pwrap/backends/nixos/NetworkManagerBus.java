package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import sun.misc.Signal;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Readiness and lifetime use the same duplex descriptor as xdg-dbus-proxy. */
public final class NetworkManagerBus {
    
    public static int run(List<String> arguments) throws Exception {
        require(!arguments.isEmpty(), "Missing D-Bus proxy executable");
        Posix.Unix[] pair = Posix.socketPair();
        try (var parent = pair[0]; var child = pair[1]) {
            var command = Commands.join(List.of(arguments.getFirst(), "--fd=" + child.fd().n), arguments.subList(1, arguments.size()));
            try (Posix.Child process = Posix.spawn(command, System.getenv(), List.of(child.fd()), 0, 1, 2)) {
                child.close();
                require(parent.receive(1, 25000).length != 0, "NetworkManager D-Bus filter did not become ready");
                String notify = System.getenv("NOTIFY_SOCKET");
                require(notify != null, "Missing service readiness socket");
                try (var socket = Posix.Unix.connect(notify, Posix.SOCK_DGRAM(), 5000)) {
                    socket.send("READY=1".getBytes(StandardCharsets.UTF_8), 5000);
                }
                var term = new Signal("TERM"); var interrupt = new Signal("INT");
                var oldTerm = Signal.handle(term, ignored -> Posix.kill(process.pid, 15));
                var oldInt = Signal.handle(interrupt, ignored -> Posix.kill(process.pid, 2));
                try { int result = process.waitFor(); return result == -15 || result == -2 ? 0 : result; }
                finally { Signal.handle(term, oldTerm); Signal.handle(interrupt, oldInt); }
            }
        }
    }
    
}