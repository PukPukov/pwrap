package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.util.regex.Pattern;
import org.graalvm.word.WordFactory;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.Posix.*;
import static org.graalvm.nativeimage.c.type.CTypeConversion.*;

public final class FileAuthority {
    
    public record Grant(String source, String destination, boolean writable) {
        public JsonElement json() { return encode(this); }
    }
    

    public @FunctionalInterface interface Check { void check(String path, boolean writable) throws Exception; }
    public @FunctionalInterface interface Mkdir { void create(int parent, String name) throws Exception; }
    public static final List<String> FORBIDDEN = List.of("/proc", "/sys", "/dev", "/run", "/etc", "/nix/var", "/var/run");
    
    public static String physical(Fd fd) throws IOException {
        String path = Files.readSymbolicLink(Path.of(fd.path())).toString();
        require(path.startsWith("/") && !path.endsWith(" (deleted)"), "File grant no longer has a stable physical name: " + path); return path;
    }
    
    public static Fd openPath(String name, @Nullable Check create, @Nullable Mkdir mkdir) throws Exception {
        return openPath(name, create, mkdir, Posix::open);
    }
    public static Fd openPath(String name, @Nullable Check create, @Nullable Mkdir mkdir, Posix.Opener opener) throws Exception {
        require((create == null) == (mkdir == null), "Directory creation requires both validation and mkdir authority");
        String path = Fs.absolute(name); List<String> parts = new ArrayList<>(); Path.of(path).forEach(p -> parts.add(p.toString()));
        Fd current = opener.open(AT_FDCWD(), "/", O_PATH() | O_DIRECTORY() | O_NOFOLLOW(), 0600);
        try {
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i); int flags = O_PATH() | O_NOFOLLOW();
                if (create != null || i + 1 < parts.size()) flags |= O_DIRECTORY();
                Fd next;
                try { next = opener.open(current.n, part, flags, 0); }
                catch (IOException e) {
                    if (!(e instanceof Failure f) || f.errno != 2 || create == null || mkdir == null) throw e;
                    create.check(Path.of(physical(current), part).toString(), true); mkdir.create(current.n, part);
                    next = opener.open(current.n, part, flags, 0);
                }
                if ((stat(next.n).mode() & 0170000) == 0120000) { next.close(); throw new IOException("File grant must not follow a symlink: " + path); }
                current.close(); current = next;
            }
            return current;
        } catch (Exception e) { current.close(); throw e; }
    }
    
    public static Fd openPath(String path) throws Exception { return openPath(path, null, null); }
    
    public static void validate(String path, String home) throws IOException {
        for (String p : List.of(path, Fs.real(path))) {
            require(
                !List.of("/", "/home", "/root", "/var", "/tmp", "/var/tmp", "/nix", "/nix/store").contains(p) && !Fs.under(home, p),
                "Refusing a whole-home/root grant: " + path
            );
            require(FORBIDDEN.stream().noneMatch(f -> Fs.under(p, f)), "Management/device path is not a file grant: " + path);
        }
    }
    
    public static void validateWrite(String path, List<String> exclusions, String home) throws IOException {
        String absolute = Fs.resolve(path, home);
        for (String forbidden : exclusions) for (String source : List.of(absolute, Fs.real(absolute)))
            for (String protectedPath : List.of(Fs.resolve(forbidden, home), Fs.real(Fs.resolve(forbidden, home))))
                require(!Fs.under(source, protectedPath) && !Fs.under(protectedPath, source), "Refusing forbidden write grant: " + path);
    }
    public static Check validator(String home, List<String> exclusions) {
        return (path, writable) -> { validate(path, home); if (writable) validateWrite(path, exclusions, home); };
    }
    /** An acquired inode and its validated destination/access, not a mount command. */
    public record Pinned(Grant grant, Fd fd) {}
    public static final class Pins implements AutoCloseable {
        private final List<Pinned> bindings = new ArrayList<>();
        public List<Pinned> bindings() { return List.copyOf(bindings); }
        public List<Fd> fds() { return bindings.stream().map(Pinned::fd).toList(); }
        public Pins(List<Grant> grants, Check validate) throws Exception {
            this(grants, validate, Posix::open);
        }
        public Pins(List<Grant> grants, Check validate, Posix.Opener opener) throws Exception {
            try {
                for (Grant grant : grants) {
                    Fd fd = openPath(grant.source, null, null, opener); bindings.add(new Pinned(grant, fd));
                    validate.check(grant.source, grant.writable); validate.check(physical(fd), grant.writable);
                }
            } catch (Exception e) { close(); throw e; }
        }
        public void close() { bindings.forEach(b -> b.fd.close()); bindings.clear(); }
    }
    public static void cap0() throws IOException {
        require(getuid() == 0 && geteuid() == 0 && getgroups(0, WordFactory.nullPointer()) == 0, "File verifier requires uid0 without groups");
        String status = Files.readString(Path.of("/proc/self/status"));
        for (String field : List.of("CapEff", "CapPrm", "CapBnd", "CapAmb"))
            require(Pattern.compile("(?m)^" + field + ":\\s*0000000000000000$").matcher(status).find(), "File verifier requires cap0: " + field);
    }
    
    public static List<Grant> accessGrants(List<Grant> grants, List<Fd> pins) throws Exception {
        require(grants.size() == pins.size(), "Pinned preflight descriptor count mismatch");
        for (int i = 0; i < grants.size(); i++) try (Fd reopened = openPath(grants.get(i).source)) {
            Inode a = stat(reopened.n), b = stat(pins.get(i).n);
            require(a.dev() == b.dev() && a.ino() == b.ino(), "Grant inode changed before pinned DAC preflight: " + grants.get(i).source);
        }
        List<Grant> checked = new ArrayList<>();
        for (int i = 0; i < grants.size(); i++) checked.add(new Grant(pins.get(i).path(), grants.get(i).destination, grants.get(i).writable));
        return List.copyOf(checked);
    }
    
    public static void checkAccess(List<Grant> grants) throws IOException {
        cap0();
        for (Grant grant : grants) {
            int mode = ((Number) Files.getAttribute(Path.of(grant.source), "unix:mode")).intValue() & 0170000;
            int access = mode == 0140000 ? 2 : 4 | (mode == 0040000 ? 1 : 0) | (grant.writable ? 2 : 0);
            require(
                Posix.access(grant.source, access),
                "Permission denied without DAC capabilities: " + grant.source + "; fix only this grant's ownership/ACL after backing it up"
            );
        }
    }
    
    public record MkdirRequest(String source, int fd, String name) {}
    private record Access(List<String> paths, boolean writable) {}
    public static void validateLayout(List<Grant> grants) {
        for (Grant read : grants) if (!read.writable()) for (Grant write : grants) if (write.writable())
            require(!Fs.under(read.destination(), write.destination()) && !Fs.under(write.destination(), read.destination()),
                "Read-only grant overlaps writable data: " + read.destination() + " / " + write.destination()
            );
    }
    
    /** A fresh-namespace destination must not traverse any host-backed grant,
     * including read-only trees whose symlinks could redirect it elsewhere. */
    public static String privateDestination(String path, Set<? extends String> mounted) {
        require(path.startsWith("/") && Fs.absolute(path).equals(path), "Generated configuration needs a canonical absolute path: " + path);
        require(!Fs.under(path, mounted), "Generated configuration overlaps a mounted resource: " + path);
        return path;
    }
    public static List<Grant> prepare(Policy.FileAccess files, String home, Mkdir mkdir) throws Exception {
        Check validate = validator(home, files.forbiddenWrites()); List<Grant> grants = new ArrayList<>();
        var groups = List.of(new Access(files.readOnly(), false), new Access(files.writable(), true), new Access(files.persist(), true));
        for (var group : groups) for (String item : group.paths()) {
            String path = Fs.resolve(item, home); boolean writable = group.writable(); validate.check(path, writable);
            grants.add(new Grant(path, path, writable));
        }
        // Check the requested layout, including missing paths, before mkdir or
        // mount setup. Command-line ordering must never change RO authority.
        validateLayout(grants);
        for (String item : files.persist()) {
            String path = Fs.resolve(item, home);
            try (Fd result = openPath(path, validate, mkdir)) { validate.check(physical(result), true); }
        }
        return grants.stream().filter(g -> Fs.exists(g.source())).toList();
    }
    
    public static int main(List<String> args) throws Exception {
        require(
            args.size() >= 2 && args.size() <= 3 && Set.of("_preflight", "_preflight-pins", "_mkdir").contains(args.getFirst()),
            "Invalid file-authority verification command"
        );
        cap0();
        if (args.getFirst().equals("_mkdir")) {
            require(args.size() == 2, "Invalid directory creation arguments");
            MkdirRequest request = decode(JsonParser.parseString(args.get(1)), MkdirRequest.class);
            String source = request.source, name = request.name; Fd fd = new Fd(request.fd);
            require(!name.isEmpty() && !Set.of(".", "..").contains(name) && !name.contains("/") && name.indexOf(0) < 0,
                "Invalid anchored directory component"
            );
            accessGrants(List.of(new Grant(source, source, true)), List.of(fd));
            try (var c = toCString(name)) { if (mkdirat(fd.n, c.get(), 0700) < 0 && errno() != 17) throw error("mkdirat"); }
            return 0;
        }
        boolean pinned = args.getFirst().equals("_preflight-pins"); require(args.size() == (pinned ? 3 : 2), "Invalid file-authority descriptor arguments");
        List<Grant> grants = decode(JsonParser.parseString(args.get(1)), new TypeToken<List<Grant>>() {});
        if (pinned) {
            List<Fd> fds = JsonParser.parseString(args.get(2)).getAsJsonArray().asList().stream().map(v -> new Fd(v.getAsInt())).toList();
            grants = accessGrants(grants, fds);
        }
        checkAccess(grants); return 0;
    }
    
}