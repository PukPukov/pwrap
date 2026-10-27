package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.JsonElement;
import ru.pukpukov.pwrap.backends.nixos.NativeEndpointChecks;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Bounded test-owned servers/namespace holders, not installed with the runtime. */
public final class HostFixtures {
    
    enum Operation { HOLD, LISTEN, ROTATE, DNS, SERVICE, APPLICATION }
    
    record Endpoints(List<HostNetwork.Check> endpoints) {}
    
    record Listening(Endpoint.Port port) {}
    
    public static void run(List<String> arguments) throws Exception {
        require(!arguments.isEmpty(), "Missing fixture operation");
        switch (Operation.valueOf(arguments.getFirst())) {
            case APPLICATION -> HostActivation.serve(arguments.subList(1, arguments.size()));
            case SERVICE -> HostEgress.serve(arguments.subList(1, arguments.size()));
            case DNS -> {
                require(arguments.size() == 4, "DNS runtime payload work");
                NativeEndpointChecks.run(Path.of(arguments.get(1)), Path.of(arguments.get(2)), Path.of(arguments.get(3)));
            }
            case HOLD -> {
                require(arguments.size() == 1, "HOLD arguments");
                Thread.sleep(180_000);
            }
            case LISTEN -> {
                require(arguments.size() >= 3, "LISTEN ready address...");
                try (var server = new HostNetwork.Listeners(arguments.subList(2, arguments.size()))) {
                    publish(Path.of(arguments.get(1)), encode(new Endpoints(server.endpoints)));
                    Thread.sleep(180_000);
                }
            }
            case ROTATE -> {
                require(arguments.size() == 5, "ROTATE control ready old-port new-port");
                Path control = Path.of(arguments.get(1)), ready = Path.of(arguments.get(2));
                int previous = new Endpoint.Port(Integer.parseInt(arguments.get(3))).value();
                int next = new Endpoint.Port(Integer.parseInt(arguments.get(4))).value();
                try (var old = new ServerSocket(previous, 32, InetAddress.getByName("127.0.0.1"))) {
                    old.setSoTimeout(50);
                    publish(ready, encode(new Listening(new Endpoint.Port(previous))));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    while (!Files.exists(control)) {
                        require(System.nanoTime() < deadline, "Relay fixture did not rotate");
                        accept(old);
                    }
                }
                try (var replacement = new ServerSocket(next, 32, InetAddress.getByName("127.0.0.1"))) {
                    replacement.setSoTimeout(100);
                    publish(ready, encode(new Listening(new Endpoint.Port(next))));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    while (System.nanoTime() < deadline) accept(replacement);
                }
            }
        }
    }
    
    private static void accept(ServerSocket server) throws IOException {
        try (var _ = server.accept()) { }
        catch (SocketTimeoutException idle) { }
    }
    
    static void publish(Path path, JsonElement document) throws IOException {
        Path temporary = path.resolveSibling(Fs.name(path) + ".new");
        Files.writeString(temporary, document.toString());
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    
}