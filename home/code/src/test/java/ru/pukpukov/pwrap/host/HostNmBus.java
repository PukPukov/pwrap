package ru.pukpukov.pwrap.host;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import static ru.pukpukov.pwrap.host.HostLifetime.await;
import static ru.pukpukov.pwrap.host.HostDbus.denied;
import ru.pukpukov.pwrap.core.*;

/** Public-marker callback regression. Never registers an agent or changes NM state. */
public final class HostNmBus {
    
    private static final String PATH = "/org/freedesktop/NetworkManager/SecretAgent";
    private static final String IFACE = "org.freedesktop.NetworkManager.SecretAgent";
    private static final String MARKER = "public-sandbox-canary-not-a-secret";
    
    private static FixtureBus.Message probe(FixtureBus client, String peer, String path, String iface, String member) throws IOException {
        return client.call(peer, path, iface, member, "", new FixtureBus.Writer());
    }
    
    public static void check(String hostAddress, String proxyAddress) throws Exception {
        try (var host = new FixtureBus(hostAddress); var client = new FixtureBus(proxyAddress); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var finished = new AtomicBoolean();
            var server = threads.submit(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!finished.get()) {
                        require(System.nanoTime() < deadline, "Unbounded NM callback fixture");
                        if (!host.readable(100)) continue;
                        var request = host.read();
                        if (request.type() != 1) continue;
                        host.reply(request, List.of(
                            request.field(1).equals(PATH) && request.field(2).equals(IFACE) &&
                                request.field(3).equals("GetSecrets") ? MARKER : "forbidden-public-canary"
                        ));
                    }
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try {
                denied(probe(client, host.unique(), PATH, IFACE, "GetSecrets"));
                host.signal(client.unique(), "/", "org.example.CompartmentCanary", "Visible");
                await(() -> {
                    client.drain();
                    return client.signals.stream().anyMatch(message ->
                        message.field(7).equals(host.unique()) && message.field(3).equals("Visible")
                    );
                }, "Visible peer signal not delivered");
                for (int repeat = 0; repeat < 2; repeat++) {
                    var reply = probe(client, host.unique(), PATH, IFACE, "GetSecrets");
                    require(reply.type() == 2 && reply.firstString().equals(MARKER), "Exact public callback failed");
                    denied(probe(client, host.unique(), PATH, IFACE, "Forbidden"));
                    denied(probe(client, host.unique(), "/wrong/path", IFACE, "GetSecrets"));
                    denied(probe(client, host.unique(), PATH, "org.example.Other", "GetSecrets"));
                }
                System.out.println("PASS NM broker: hidden peer denied; visible peer gets only exact SecretAgent callbacks, no sticky TALK");
            } finally {
                finished.set(true);
                server.get(10, TimeUnit.SECONDS);
            }
        }
    }
    
    public static void fixture(String daemonPath, String proxyPath) throws Exception {
        Path work = Files.createTempDirectory(Path.of("/var/tmp"), "pwrap-nm-bus-test-");
        String address = "unix:path=" + work.resolve("host");
        System.out.println("Evidence: " + work);
        Process daemon = start(work, "daemon", List.of(daemonPath, "--session", "--nofork", "--address=" + address), Map.of());
        try {
            await(() -> Files.exists(work.resolve("host")), "Private NM bus not ready");
            var pair = Posix.socketPair();
            try (
                var parent = pair[0]; var child = pair[1];
                var error = Posix.open(work.resolve("proxy.log").toString(), Posix.O_RDWR() | Posix.O_CREAT())
            ) {
                var command = List.of(
                    proxyPath, "--fd=" + child.fd().n, address, work.resolve("proxy").toString(), "--filter",
                    "--call-visible=" + IFACE + ".GetSecrets@" + PATH,
                    "--call-visible=" + IFACE + ".SaveSecrets@" + PATH,
                    "--call-visible=" + IFACE + ".DeleteSecrets@" + PATH
                );
                try (var proxy = Posix.spawn(command, System.getenv(), List.of(child.fd()), 0, error.n, error.n)) {
                    child.close();
                    require(parent.receive(1, 5000).length == 1, "Private NM proxy not ready");
                    try { check(address, "unix:path=" + work.resolve("proxy")); }
                    finally {
                        Posix.kill(proxy.pid, 15);
                        proxy.waitFor();
                    }
                }
            }
        } finally {
            daemon.destroy();
            if (!daemon.waitFor(3, TimeUnit.SECONDS)) daemon.destroyForcibly().waitFor();
        }
    }
    
}