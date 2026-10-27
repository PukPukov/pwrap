package ru.pukpukov.pwrap.backends.nix;

import module java.base;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import ru.pukpukov.pwrap.core.Fs;

/** Export launch metadata only; never expose original executables or activation services. */
public final class PackageExport {
    
    private static final Pattern COMMAND = Pattern.compile("^(Exec|TryExec)=(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s\"]+)(.*)$");
    
    public static @Nullable String desktopEntry(String text, String wrapper, Set<String> names) {
        List<String> lines = new ArrayList<>();
        boolean executable = false;
        for (String line : text.lines().toList()) {
            int separator = line.indexOf('=');
            String key = separator < 0 ? "" : line.substring(0, separator);
            if (key.equals("Exec") || key.equals("TryExec")) {
                var match = COMMAND.matcher(line);
                if (!match.matches()) return null;
                String token = match.group(2), suffix = match.group(3);
                if (token == null || suffix == null) return null;
                if (token.startsWith("\"") && token.endsWith("\"")) token = token.substring(1, token.length() - 1);
                Path name = Path.of(token).getFileName();
                if (name == null || !names.contains(name.toString())) return null;
                executable |= key.equals("Exec");
                String quoted = wrapper.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$");
                // TryExec is a key-file path string, not an Exec command line.
                if (key.equals("TryExec") && !suffix.isBlank()) return null;
                line = key.equals("TryExec") ? "TryExec=" + wrapper.replace("\\", "\\\\") : "Exec=\"" + quoted + "\"" + suffix;
            } else if (key.equals("DBusActivatable")) line = "DBusActivatable=false";
            lines.add(line);
        }
        return executable ? String.join("\n", lines) + "\n" : null;
    }
    
    public static void export(Path pack, Path output, String executable, List<String> aliases) throws IOException {
        Set<String> names = new HashSet<>(aliases);
        names.add(executable);
        names.add(executable.toLowerCase(Locale.ROOT));
        for (String relative : List.of("share/icons", "share/pixmaps")) {
            Path source = pack.resolve(relative), destination = output.resolve(relative);
            if (Files.isDirectory(source)) {
                Files.createDirectories(Fs.parent(destination));
                Files.createSymbolicLink(destination, source);
            }
        }
        Path applications = pack.resolve("share/applications");
        if (!Files.isDirectory(applications)) return;
        try (var files = Files.newDirectoryStream(applications, "*.desktop")) {
            List<Path> sorted = new ArrayList<>();
            files.forEach(sorted::add);
            Collections.sort(sorted);
            for (Path source : sorted) {
                String text = desktopEntry(Files.readString(source), output.resolve("bin").resolve(executable).toString(), names);
                if (text == null) continue;
                Path destination = output.resolve("share/applications").resolve(Fs.name(source));
                Files.createDirectories(Fs.parent(destination));
                Files.writeString(destination, text);
            }
        }
    }
}