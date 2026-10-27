package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.FileAuthority.*;

/** test_security_context.py: explicit session identity and no-follow acquisition under races. */
@EnabledInNativeImage
class SecurityContextTest {
    final Path root, selected, privateDir;

    SecurityContextTest(@TempDir Path root) throws IOException {
        this.root = root;
        selected = Files.createDirectory(root.resolve("selected"));
        privateDir = Files.createDirectory(root.resolve("private"));
        Files.writeString(privateDir.resolve("secret"), "canary");
    }

    @Test void test_prepared_home_cannot_be_hidden_by_service_home() {
        assertThrows(IllegalArgumentException.class, () -> validate(selected.toString(), selected.toString()));
    }

    @Test void test_tilde_forbidden_writes_uses_prepared_not_service_home() {
        assertThrows(IllegalArgumentException.class, () -> validateWrite("/session-home/controller/child", List.of("~/controller"), "/session-home"));
    }

    @Test void test_initial_alias_to_nonexcluded_private_data_denied_for_both_modes() throws IOException {
        Path alias = Files.createSymbolicLink(selected.resolve("alias"), privateDir);
        for (boolean writable : List.of(false, true)) for (Path path : List.of(alias, alias.resolve("secret")))
            assertThrows(IOException.class, () -> new Pins(List.of(new Grant(path.toString(), "/data", writable)), (var _, var _) -> {}));
    }

    @Test void test_persist_cannot_create_beneath_initial_nonexcluded_alias() throws IOException {
        Path alias = Files.createSymbolicLink(root.resolve("alias"), privateDir);
        assertThrows(IOException.class, () -> openPath(
            alias.resolve("new/deep").toString(), (var _, var _) -> {},
            (fd, name) -> { throw new AssertionError("Initial alias reached mkdir"); }
        ));
        assertFalse(Files.exists(privateDir.resolve("new")));
    }

    @Test void test_alias_planted_between_validation_and_open_denied() {
        boolean[] changed = {false};
        Posix.Opener race = (fd, name, flags, mode) -> {
            if (name.equals("selected") && !changed[0]) {
                changed[0] = true;
                Files.delete(selected);
                Files.createSymbolicLink(selected, privateDir);
            }
            return Posix.open(fd, name, flags, mode);
        };
        assertThrows(IOException.class, () -> new Pins(
            List.of(new Grant(selected.toString(), "/data", true)), (var _, var _) -> {}, race
        ));
        assertTrue(changed[0]);
    }

    @Test void test_prepared_context_rejects_missing_or_divergent_home() {
        for (Map<String, String> environment : List.<Map<String, String>>of(
            Map.of(), Map.of("HOME", "relative"), Map.of("HOME", "/good", "XDG_RUNTIME_DIR", "relative")
        )) {
            assertThrows(IllegalArgumentException.class, () -> Session.from(environment));
        }
        // There is no second "prepared home" anymore: identity is derived from the captured session.
        assertEquals("/good", new Session.Environment(Map.of("HOME", "/good")).session().home());
        assertThrows(IllegalArgumentException.class, () -> Json.decode(
            JsonParser.parseString("{\"home\":\"/other\",\"values\":{\"HOME\":\"/good\"}}"),
            Session.Environment.class
        ));
    }

    @Test void test_persist_concurrent_creator_symlink_is_never_followed() {
        Path child = selected.resolve("new");
        assertThrows(IOException.class, () -> openPath(
            child.resolve("nested").toString(), (var _, var _) -> {},
            (parent, name) -> Files.createSymbolicLink(Path.of("/proc/self/fd/" + parent, name), privateDir)
        ));
        assertFalse(Files.exists(privateDir.resolve("nested")));
        assertTrue(Files.isSymbolicLink(child));
    }
}