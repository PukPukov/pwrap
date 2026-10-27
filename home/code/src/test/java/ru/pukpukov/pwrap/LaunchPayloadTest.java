package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nixos.Network;
import ru.pukpukov.pwrap.backends.nixos.Sandbox;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.core.Endpoint.*;

/** test_launch_payload.py: actual namespace-descriptor transfer/readiness, no network IO. */
@EnabledInNativeImage
class LaunchPayloadTest {
    
    @Test void test_download_directory_setting_is_private_and_uses_the_granted_path(@TempDir Path root) throws Exception {
        Path settings = root.resolve("settings-copy");
        Path bwrap = script(root.resolve("bwrap"),
            "for arg do case \"$arg\" in */user-dirs.dirs) [ ! -f \"$arg\" ] || " + tool("cat") + " \"$arg\" > " + quote(settings) + ";; esac; done\n"
        );
        JsonObject raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("bwrap", bwrap.toString());
        raw.addProperty("filter", "/dev/null");
        var p = prepared(
            Policy.read(obj("command", List.of("/command"), "downloads", "~/Загрузки/telegram")),
            new Session.Environment(Map.of("HOME", "/home/test"))
        );
        Config config = new Config(raw);
        assertEquals(0, Payload.run(p, config, null, new Sandbox(config.platform, new Lifetime(root))));
        assertEquals("XDG_DOWNLOAD_DIR=\"/home/test/Загрузки/telegram\"\n", Files.readString(settings));
    }
    
    @Test void test_slirp_setup_uses_generic_engine_and_closes_namespace_descriptors(@TempDir Path root) throws Exception {
        Path appArgs = root.resolve("app-args"), helperArgs = root.resolve("helper-args");
        Path helperPid = root.resolve("helper-pid"), unblocked = root.resolve("unblocked");
        Path bwrap = script(root.resolve("bwrap"),
            """
            info= block= ready=
            previous=
            for arg do
              case "$previous" in --info-fd) info=$arg;; --block-fd) block=$arg;; esac
              case "$arg" in --ready-fd=*) ready=${arg#*=};; esac
              previous=$arg
            done
            if [ -n "$info" ]; then
              printf '%%s\\n' "$@" > %s
              printf '{"child-pid":%%s,"ipc-namespace":"' "$$" >&$info
              %s 0.1
              printf 'fixture"}\\n' >&$info
              exec {info}>&-
              IFS= read -r -n1 -u "$block" byte || exit 41
              printf '%%s' "$byte" > %s
              exit 0
            fi
            printf '%%s\\n' "$@" > %s
            printf '%%s' "$$" > %s
            [ -n "$ready" ] || exit 42
            printf 1 >&$ready
            exec %s 30
            """.formatted(quote(appArgs), tool("sleep"), quote(unblocked), quote(helperArgs), quote(helperPid), tool("sleep"))
        );
        JsonObject raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("bwrap", bwrap.toString());
        raw.addProperty("filter", "/dev/null");
        raw.addProperty("scope", script(root.resolve("scope"), "exec \"$@\"\n").toString());
        raw.addProperty("slirpClosure", Files.writeString(root.resolve("closure"), "").toString());
        Config config = new Config(raw);
        var p = prepared(Policy.read(obj("command", List.of("/command"), "network", "internet")), new Session.Environment(Map.of("HOME", "/home/test")));
        var boundary = new Network.Boundary(
            new Network.Request(1, config.platform.entry, p.policy().network(), config.platform.network()),
            new Network.Capture(List.of(), "nameserver 192.0.2.1\n", List.of(new Address("192.0.2.1")))
        );
        assertEquals(0, Payload.run(p, config, boundary, new Sandbox(config.platform, new Lifetime(root))));
        assertEquals("1", Files.readString(unblocked));
        var app = Files.readAllLines(appArgs);
        assertTrue(app.contains("--info-fd") && app.contains("--block-fd"));
        var helper = Files.readAllLines(helperArgs);
        for (String flag : List.of("--enable-sandbox", "--enable-seccomp", "--disable-host-loopback")) assertTrue(helper.contains(flag));
        assertTrue(helper.stream().anyMatch(s -> s.startsWith("--userns-path=/proc/self/fd/")));
        assertTrue(helper.stream().anyMatch(s -> s.startsWith("/proc/self/fd/")));
        long pid = Long.parseLong(Files.readString(helperPid));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Slirp fixture was not terminated/reaped");
    }
    
}