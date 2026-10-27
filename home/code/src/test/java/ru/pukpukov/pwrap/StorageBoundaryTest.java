package ru.pukpukov.pwrap;

import module java.base;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.RuntimeFixture.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pukpukov.pwrap.core.*;

/** A selected storage implementation, not a native fallback inside Nix storage. */
class StorageBoundaryTest {
    
    @Test void platform_sources_cannot_import_a_concrete_storage_or_assume_its_layout() throws IOException {
        Path platform = Path.of("home/code/src/main/java/ru/pukpukov/pwrap/backends/nixos");
        try (var files = Files.walk(platform)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("ru.pukpukov.pwrap.backends.nix."), file.toString());
                assertFalse(source.contains("/nix/store"), file.toString());
                assertFalse(source.contains("ru.pukpukov.pwrap.Config"), file.toString());
                assertFalse(source.contains("nixStore"), file.toString());
            }
        }
        assertFalse(Files.readString(Path.of("home/code/src/main/java/ru/pukpukov/pwrap/Payload.java")).contains("/nix/store"));
    }
    
    @Test void outside_store_code_reaches_normal_preparation_without_a_nix_query(@TempDir Path root) throws Exception {
        Path app = script(root.resolve("application"), "exit 0\n");
        var raw = descriptor(root.resolve("absent"), obj());
        raw.addProperty("storageBackend", "test-storage");
        raw.remove("nixStore");
        Config config = new Config(raw);
        Storage.Runtime projection = new Storage.Runtime(
            List.of(), List.of(new Resources.Binding(app.toString(), app.toString())),
            List.of(), List.of(), "/application/bin", List.of("/application")
        );
        int[] calls = {0};
        Storage selected = new Storage() {
            
            public Provenance provenance() {
                return new Provenance() {
                    public boolean immutable(String path) { return false; }
                    public boolean boundary(String path) { return false; }
                };
            }
            
            public Runtime resolve(Policy policy, List<FileAuthority.Grant> grants, Inputs resources) {
                calls[0]++;
                assertEquals(app.toString(), policy.command().getFirst());
                assertTrue(grants.isEmpty());
                assertEquals(Inputs.empty(), resources);
                return projection;
            }
        };
        try (var lifetime = new Lifetime()) {
            var p = Launch.prepare(
                Policy.read(obj("execution", "direct", "home", root.resolve("home").toString(), "command", List.of(app.toString()))),
                config, lifetime, selected
            );
            assertSame(projection, p.runtime());
            assertEquals(1, calls[0]);
            assertEquals("/", p.cwd());
        }
        // External descriptor names cannot load a test implementation or trigger
        // a native fallback. Only trusted composition may supply the resolver.
        try (var lifetime = new Lifetime()) { assertThrows(IllegalArgumentException.class, () -> Launch.prepare(
            Policy.read(obj("execution", "direct", "home", root.resolve("home").toString(), "command", List.of(app.toString()))),
            config, lifetime
        )); }
        assertThrows(UnsupportedOperationException.class, () -> projection.files().clear());
    }
    
}