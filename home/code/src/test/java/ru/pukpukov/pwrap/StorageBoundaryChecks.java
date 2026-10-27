package ru.pukpukov.pwrap;

import module java.base;
import ru.pukpukov.pwrap.backends.nixos.PlatformConfig;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Ordinary outside-store program through actual preparation and bwrap, no fallback. */
public final class StorageBoundaryChecks {
    
    public static void run(Config installed, Path work) throws Exception {
        Path sibling = Files.writeString(work.resolve("unselected"), "not granted");
        Path app = script(
            work.resolve("application"),
            "set -eu\ntest \"$1\" = 'one argument'\n" +
                "test ! -e " + quote(sibling) + "\ntest ! -e /run/systemd/private\n"
        );
        // Only pwrap's Nix-built helpers use this packaged closure. The target
        // application is not added to the store or sent to nix-store --query.
        List<String> helpers = new ArrayList<>(NixStorage.readClosure(installed.platform.path(PlatformConfig.Key.BASE_CLOSURE)));
        JsonObject raw = JsonParser.parseString(installed.platform.identity).getAsJsonObject();
        raw.add("metadata", obj());
        raw.addProperty("hostConfigDir", work.resolve("absent").toString());
        raw.addProperty("storageBackend", "outside-store-test");
        raw.remove("nixStore");
        Config config = new Config(raw);
        int[] calls = {0};
        Storage selected = new Storage() {
            public Provenance provenance() { return new Provenance() {
                public boolean immutable(String path) { return false; }
                public boolean boundary(String path) { return false; }
            }; }
            public Runtime resolve(Policy policy, List<FileAuthority.Grant> grants, Inputs resources) {
                calls[0]++;
                require(policy.command().getFirst().equals(app.toString()) && grants.isEmpty()
                    && resources.equals(Inputs.empty()), "Wrong substitution contract inputs"
                );
                return new Runtime(
                    helpers, List.of(new Resources.Binding(app.toString(), app.toString())), List.of(), List.of(),
                    config.platform.path(PlatformConfig.Key.TOOLBOX) + "/bin", List.of("/nix/store")
                );
            }
        };
        try (var lifetime = new Lifetime()) {
            var p = Launch.prepare(
                Policy.read(obj(
                    "execution", "direct", "home", work.resolve("home").toString(),
                    "command", List.of(app.toString(), "one argument")
                )),
                config, lifetime, selected
            );
            require(Payload.run(p, config, null) == 0 && calls[0] == 1, "Outside-store execution failed");
        }
        System.out.println(
            "PASS: selected outside-store executable through normal preparation and actual sandbox; " +
            "helpers stay Nix-built, no target Nix query"
        );
    }
    
}