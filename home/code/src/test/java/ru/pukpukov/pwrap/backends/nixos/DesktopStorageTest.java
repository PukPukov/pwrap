package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real resource acquisition using a different storage's provenance rules. */
@EnabledInNativeImage
class DesktopStorageTest {
    
    @Test void resource_provenance_is_selected_not_hardcoded_to_nix(@TempDir Path work) throws Exception {
        Path immutable = Files.createDirectory(work.resolve("resource-root"));
        Path icons = Files.createDirectories(immutable.resolve("share/icons"));
        Path icon = Files.writeString(icons.resolve("one.svg"), "selected");
        Path privateFile = Files.writeString(immutable.resolve("private"), "not selected");
        Path alias = Files.createSymbolicLink(work.resolve("icons"), icons);
        Storage.Provenance provenance = new Storage.Provenance() {
            public boolean immutable(String path) { return path.startsWith(immutable + "/"); }
            public boolean boundary(String path) { return path.equals(immutable.toString()); }
        };
        var selected = new Desktop.Selection(work.resolve("home").toString(), provenance);
        selected.tree(alias.toString());
        var result = selected.appearance(new Desktop.Supplement(Map.of(), List.of(), Map.of(), List.of()), Map.of(), "");
        assertEquals(Map.of(alias.toString(), icons.toString(), icons.toString(), icons.toString()), result.files());
        assertTrue(Files.exists(icon));
        assertFalse(result.files().containsValue(privateFile.toString()));
        assertThrows(IllegalArgumentException.class, () -> selected.tree(immutable.toString()));
        var untrusted = new Desktop.Selection(work.resolve("home").toString(), new Storage.Provenance() {
            public boolean immutable(String path) { return false; }
            public boolean boundary(String path) { return false; }
        });
        assertThrows(IllegalArgumentException.class, () -> untrusted.tree(alias.toString()));
    }
    
}