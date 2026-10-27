package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import ru.pukpukov.pwrap.core.Fs;
import ru.pukpukov.pwrap.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Direct port of test_package_export.py. */
class PackageExportTest {
    
    final Path root;
    
    PackageExportTest(@TempDir Path root) { this.root = root; }
    
    @Test void test_every_launch_command_including_actions_must_be_wrappable() {
        String known = "[Desktop Entry]\nExec=/original/bin/App %U\nTryExec=app\nDBusActivatable=true\n";
        String transformed = PackageExport.desktopEntry(known, "/wrapped/bin/app", Set.of("App", "app"));
        Json.require(transformed != null, "Expected fixture value");
        for (String expected : List.of(
            "Exec=\"/wrapped/bin/app\" %U", "TryExec=/wrapped/bin/app\n", "DBusActivatable=false"
        )) assertTrue(transformed.contains(expected));
        for (String text : List.of(
            known + "[Desktop Action Other]\nExec=/original/libexec/helper\n",
            "[Desktop Entry]\nExec=env NAME=value app\n",
            "[Desktop Entry]\nName=No command\n"
        )) assertNull(PackageExport.desktopEntry(text, "/wrapped/bin/app", Set.of("App", "app")));
    }
    
    @Test void test_tryexec_is_a_path_not_a_quoted_command_line() {
        String text = "[Desktop Entry]\nExec=app %U\nTryExec=app\n";
        String entry = PackageExport.desktopEntry(text, "/wrapped directory/bin/app", Set.of("app"));
        Json.require(entry != null, "Expected fixture value");
        assertTrue(entry.contains("Exec=\"/wrapped directory/bin/app\" %U\n"));
        assertTrue(entry.contains("TryExec=/wrapped directory/bin/app\n"));
        assertNull(PackageExport.desktopEntry(text.replace("TryExec=app", "TryExec=app --argument"), "/wrapped/bin/app", Set.of("app")));
    }
    
    @Test void test_export_contains_no_original_bin_libexec_or_activation_services() throws Exception {
        Path pack = root.resolve("package"), output = root.resolve("wrapper");
        for (String name : List.of(
            "bin/app", "bin/other", "libexec/helper", "share/dbus-1/services/app.service",
            "share/icons/app.svg", "share/applications/known.desktop", "share/applications/unknown.desktop"
        )) {
            Path path = pack.resolve(name);
            Files.createDirectories(Fs.parent(path));
            Files.writeString(path, name.endsWith("known.desktop") ? "Exec=app %U\n" : "data");
        }
        Files.writeString(pack.resolve("share/applications/unknown.desktop"), "Exec=helper\n");
        PackageExport.export(pack, output, "app", List.of());
        assertTrue(Files.exists(output.resolve("share/icons/app.svg")));
        assertTrue(Files.exists(output.resolve("share/applications/known.desktop")));
        for (String relative : List.of("bin", "libexec", "share/dbus-1", "share/applications/unknown.desktop"))
            assertFalse(Files.exists(output.resolve(relative)), relative);
    }
    
}