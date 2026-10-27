package ru.pukpukov.pwrap.core;

import module java.base;
import java.nio.file.attribute.PosixFilePermissions;

public final class Fs {
    
    public static Path parent(Path path) {
        Path parent = path.getParent(); Json.require(parent != null, "Expected a path with a parent: " + path); return parent;
    }
    public static String name(Path path) {
        Path name = path.getFileName(); Json.require(name != null, "Expected a non-root path: " + path); return name.toString();
    }
    public static String absolute(String path) { return Path.of(path).toAbsolutePath().normalize().toString(); }
    public static String resolve(String path, String home) { return absolute(path.startsWith("~/") ? home + path.substring(1) : path); }
    public static boolean exists(String path) { return Files.exists(Path.of(path), LinkOption.NOFOLLOW_LINKS); }
    public static boolean under(String path, String root) {
        return path.startsWith(root) && (path.length() == root.length() || root.equals("/") || path.charAt(root.length()) == '/');
    }
    /** Exact ancestor lookup, without scanning every unrelated root. */
    public static boolean under(String path, Set<? extends String> roots) {
        if (roots.contains(path) || path.startsWith("/") && roots.contains("/")) return true;
        for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1))
            if (roots.contains(path.substring(0, slash))) return true;
        return false;
    }
    /** Like realpath(strict=False), including symlinked parents of missing leaves. */
    public static String real(String path) throws IOException {
        Path value = Path.of(path).toAbsolutePath(), current = Path.of("/");
        Deque<String> parts = new ArrayDeque<>(); value.forEach(p -> parts.add(p.toString())); int links = 0;
        while (!parts.isEmpty()) {
            String part = parts.removeFirst();
            if (part.equals(".")) continue;
            if (part.equals("..")) { if (current.getParent() != null) current = current.getParent(); continue; }
            Path next = current.resolve(part);
            if (!Files.isSymbolicLink(next)) { current = next; continue; }
            if (++links > 40) throw new IOException("Too many symbolic links: " + path);
            Path target = Files.readSymbolicLink(next);
            var pending = new ArrayList<String>(); target.forEach(p -> pending.add(p.toString()));
            for (int i = pending.size() - 1; i >= 0; i--) parts.addFirst(pending.get(i));
            if (target.isAbsolute()) current = Path.of("/");
        }
        return current.toString();
    }
    public static void write(Path path, String text) throws IOException { Files.writeString(path, text); }
    public static void mode(Path path, int mode) throws IOException { Files.setAttribute(path, "unix:mode", mode); }
    public static void mkdir(Path path) throws IOException {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    
}