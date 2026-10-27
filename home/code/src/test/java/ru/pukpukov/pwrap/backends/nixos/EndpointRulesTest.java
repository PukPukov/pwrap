package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.Launch;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.Endpoint.*;

/** Remaining test_endpoint_rules.py: real unit serialization, with a non-executing manager fixture. */
@EnabledInNativeImage
class EndpointRulesTest {
    
    final Path root, argv, wire;
    final PlatformConfig config;
    
    EndpointRulesTest(@TempDir Path root) throws IOException {
        this.root = root;
        argv = root.resolve("argv"); wire = root.resolve("unit");
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("systemdRun", script(
            root.resolve("systemd-run"),
            "printf '%s\\n' \"$@\" > " + quote(argv) + "\nfor last do :; done\n" + tool("cat") + " \"$last\" > " + quote(wire) + "\n"
        ).toString());
        config = new PlatformConfig(raw);
    }
    
    private Launch.Prepared policy(String name, Policy.Mode mode, boolean loopback) {
        var input = obj("name", name, "command", List.of("/app"), "network", mode.name().toLowerCase(Locale.ROOT), "allowLocalhost", loopback);
        if (mode == Policy.Mode.LOCAL) input.addProperty("allowLAN", true);
        return prepared(Policy.read(input), new Session.Environment(Map.of("HOME", "/session-home")));
    }
    
    private Network.Boundary boundary(Launch.Prepared p) throws Exception {
        var settings = new Policy.NetworkSettings(List.of(new Port(3128)), List.of());
        var capture = Network.capture(
            p.policy().network().mode(), () -> settings.localDeniedPorts(),
            () -> new Egress.Resolver("nameserver 127.0.0.53\n", List.of(new Address("127.0.0.53")))
        );
        return new Network.Boundary(new Network.Request(1, config.entry, p.policy().network(), settings), capture);
    }
    
    @Test void test_declared_ports_do_not_masquerade_as_protocol_defaults() throws Exception {
        assertEquals(List.of(), Network.deniedPorts(config, config.network(), root, command -> { throw new AssertionError(command); }));
        assertEquals(List.of(Port.DNS, new Port(3128)), Network.deniedPorts(
            config, new Policy.NetworkSettings(List.of(Port.DNS, new Port(3128)), List.of()), root,
            command -> { throw new AssertionError(command); }
        ));
    }
    
    @Test void test_every_restricted_mode_captures_the_same_declared_exclusions() throws Exception {
        for (var mode : Policy.Mode.values()) {
            int[] calls = {0, 0};
            var capture = Network.capture(
                mode,
                () -> {
                    calls[0]++;
                    return List.of(new Port(3128));
                },
                () -> {
                    calls[1]++;
                    return new Egress.Resolver("nameserver 127.0.0.53\n", List.of(new Address("127.0.0.53")));
                }
            );
            boolean restricted = Set.of(Policy.Mode.INTERNET, Policy.Mode.LAN, Policy.Mode.LOCAL).contains(mode);
            assertEquals(restricted ? 1 : 0, calls[0]);
            assertEquals(Set.of(Policy.Mode.INTERNET, Policy.Mode.LAN, Policy.Mode.HOST).contains(mode) ? 1 : 0, calls[1]);
            assertEquals(restricted ? List.of(new Port(3128)) : List.of(), capture.deniedPorts());
            
            var p = policy("run", mode, false);
            assertEquals(0, Platform.runUnit(p, boundary(p), config, root.resolve("runtime"), new Lifetime(root)));
            var command = Files.readAllLines(argv);
            assertTrue(command.contains("RestrictRealtime=yes"));
            assertEquals(mode == Policy.Mode.NONE, command.contains("IPAddressDeny=any"));
            assertTrue(command.stream().noneMatch(s -> s.startsWith("IPAddressAllow=")));
            assertEquals(capture, Json.decode(Json.read(wire), Platform.Unit.class).boundary().capture());
        }
    }
    
    @Test void test_platform_preserves_launch_snapshot_and_installs_poststop_release() throws Exception {
        var base = policy("cohort", Policy.Mode.NONE, false);
        var request = new Launch.Request(base.policy().authority(), "/app", config.identity, null, null, base.session().values());
        var snapshot = new Instances.Snapshot<Launch.Request, Launch.Capture>(request, new Launch.Capture(base.session(), base.desktop(), null), null);
        var p = new Launch.Prepared(
            base.policy(), base.session(), base.desktop(), null, base.audio(), List.of(), base.runtime(), Map.of(), base.cwd(), null, snapshot
        );
        assertEquals(0, Platform.runUnit(p, boundary(p), config, root.resolve("runtime"), new Lifetime(root)));
        var unit = Json.decode(Json.read(wire), Platform.Unit.class);
        assertEquals(Json.encode(snapshot), unit.snapshots().get(Launch.CODEC.key()));
        assertTrue(unit.snapshots().containsKey(Network.CODEC.key()));
        var release = Files.readAllLines(argv).stream().filter(s -> s.startsWith("ExecStopPost=")).toList();
        assertEquals(1, release.size());
        assertTrue(release.getFirst().contains(" _release cohort " + unit.unit()));
        assertTrue(release.getFirst().contains(config.entry));
    }
    
    @Test void test_missing_resolved_constraints_fail_closed_not_empty_list_fallback() throws Exception {
        var p = policy("run", Policy.Mode.INTERNET, false);
        var unit = new Platform.Unit(p, boundary(p), "pwrap-test.service", new Egress.Name("pwrap_" + "1".repeat(32)), new Port(54321));
        var raw = Json.encode(unit).getAsJsonObject();
        raw.remove("boundary");
        assertThrows(IllegalArgumentException.class, () -> Json.decode(raw, Platform.Unit.class));
    }
    
}