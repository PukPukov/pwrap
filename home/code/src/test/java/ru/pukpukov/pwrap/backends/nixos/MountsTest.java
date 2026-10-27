package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import ru.pukpukov.pwrap.core.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledInNativeImage
class MountsTest {
    
    @Test void authority_uses_only_acquired_descriptors_and_keeps_access_and_order(@TempDir Path root) throws Exception {
        Path read = Files.writeString(root.resolve("read"), "read"), write = Files.createDirectory(root.resolve("write"));
        var grants = List.of(new FileAuthority.Grant(read.toString(), "/input", false), new FileAuthority.Grant(write.toString(), "/output", true));
        try (var pins = new FileAuthority.Pins(grants, FileAuthority.validator("/session-home", List.of()))) {
            var fds = pins.fds();
            long inode = Posix.stat(fds.getFirst().n).ino();
            Files.move(read, root.resolve("renamed"));
            assertEquals(List.of("--ro-bind-fd", "" + fds.getFirst().n, "/input", "--bind-fd", "" + fds.getLast().n, "/output"), Mounts.authority(pins));
            assertEquals(inode, Posix.stat(fds.getFirst().n).ino());
        }
    }
    
}