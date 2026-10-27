package ru.pukpukov.pwrap.core;

import module java.base;
import org.junit.jupiter.api.Test;
import ru.pukpukov.pwrap.core.FileAuthority.Grant;
import static org.junit.jupiter.api.Assertions.*;

/** Pure authority contracts: no mount operations or vulnerable controls. */
class FileLayoutTest {
    
    @Test void readOnlyAndWritableTreesMustBeDisjointInEitherOrder() {
        for (var pair : List.of(
            List.of(new Grant("/repo/.git", "/repo/.git", false), new Grant("/repo", "/repo", true)),
            List.of(new Grant("/repo", "/repo", false), new Grant("/repo/data", "/repo/data", true)),
            List.of(new Grant("/repo", "/repo", false), new Grant("/repo", "/repo", true))
        )) {
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.validateLayout(pair));
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.validateLayout(pair.reversed()));
        }
        FileAuthority.validateLayout(List.of(new Grant("/repo", "/repo", false), new Grant("/repository", "/repository", true)));
    }
    
    @Test void generatedConfigurationRequiresFreshCanonicalDestinations() {
        var mounted = Set.of("/config", "/appearance", "/proc", "/tmp");
        for (String path : List.of(
            "relative", "/config/../private", "/config//file",
            "/config/file", "/appearance/file", "/proc/self/fd/3/file", "/tmp/config"
        ))
            assertThrows(IllegalArgumentException.class, () -> FileAuthority.privateDestination(path, mounted));
        assertEquals("/private/mimeapps.list", FileAuthority.privateDestination("/private/mimeapps.list", mounted));
        assertEquals("/configs/keyfile", FileAuthority.privateDestination("/configs/keyfile", mounted));
        assertThrows(IllegalArgumentException.class, () -> FileAuthority.privateDestination("/private/keyfile", Set.of("/private/keyfile")));
    }
    
}