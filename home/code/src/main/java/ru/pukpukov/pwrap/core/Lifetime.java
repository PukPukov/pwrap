package ru.pukpukov.pwrap.core;

import module java.base;

/** LIFO ownership for dynamically composed helpers, pins and temporary trees. */
public final class Lifetime implements AutoCloseable {

    private final Path temporaryRoot;
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();

    public Lifetime() { this(Path.of("/run")); }

    public Lifetime(Path temporaryRoot) { this.temporaryRoot = temporaryRoot; }

    public synchronized <T extends AutoCloseable> T use(T value) { resources.push(value); return value; }

    public Path temp(String prefix) throws IOException {
        Path path = Files.createTempDirectory(temporaryRoot, prefix);
        use(() -> Fs.deleteTree(path));
        return path;
    }

    public synchronized void close() throws Exception {
        Exception failure = null;
        while (!resources.isEmpty()) try { resources.pop().close(); }
        catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
    
}