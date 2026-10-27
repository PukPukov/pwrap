package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.Commands;
import static ru.pukpukov.pwrap.core.Json.*;

/** Read-only protocol checks against a private bus, never the host system bus. */
public final class NativeBusChecks {
    
    public static void run(Path root, String daemon) throws Exception {
        String address = "unix:path=" + root.resolve("dbus");
        Process process = Commands.process(List.of(
            daemon, "--session", "--nofork", "--address=" + address, "--print-address=1"
        )).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            require(required(process.inputReader().readLine(), "Expected fixture value").startsWith("unix:path="), "Private bus not ready");
            try (var bus = new Bus(address)) {
                var owner = bus.call(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus.GetNameOwner", "s", List.of("org.freedesktop.DBus")
                );
                require(owner.type() == 2 && owner.firstString().equals("org.freedesktop.DBus"), "D-Bus string reply/serial decoding failed");
                var absent = bus.call(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus.GetNameOwner", "s", List.of("org.pwrap.TestAbsent")
                );
                require(absent.type() == 3 && absent.error().equals("org.freedesktop.DBus.Error.NameHasNoOwner"), "D-Bus error decoding failed");
            }
        } finally {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
    
}