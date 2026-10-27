package ru.pukpukov.pwrap.core;

import module java.base;
import module org.graalvm.nativeimage;
import module org.graalvm.word;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.graalvm.nativeimage.c.type.CTypeConversion.*;

/** Linux capabilities Java NIO cannot express: no-follow openat, flock and passed FDs. */
@CContext(Posix.Headers.class)
public final class Posix {
    
    @FunctionalInterface public interface Opener { Fd open(int parent, String path, int flags, int mode) throws IOException; }
    
    public static final class Headers implements CContext.Directives {
        public List<String> getHeaderFiles() {
            return List.of(
                "<fcntl.h>", "<unistd.h>", "<errno.h>", "<sys/stat.h>", "<sys/file.h>", "<sys/wait.h>", "<spawn.h>",
                "<signal.h>", "<poll.h>", "<sys/socket.h>", "<sys/un.h>", "<sys/sysmacros.h>", "<dlfcn.h>"
            );
        }
        
        public List<String> getMacroDefinitions() { return List.of("_GNU_SOURCE"); }
    }
    
    @CConstant public static native int O_PATH();
    @CConstant public static native int O_RDONLY();
    @CConstant public static native int O_RDWR();
    @CConstant public static native int O_CREAT();
    @CConstant public static native int O_EXCL();
    @CConstant public static native int O_CLOEXEC();
    @CConstant public static native int O_NOFOLLOW();
    @CConstant public static native int O_DIRECTORY();
    @CConstant public static native int AT_FDCWD();
    @CFunction public static native int getuid();
    @CConstant public static native int RTLD_NOW();
    @CFunction public static native PointerBase dlopen(CCharPointer file, int flags);
    @CFunction public static native int dlclose(PointerBase handle);
    @CFunction public static native ResolveSyscall dlsym(PointerBase handle, CCharPointer name);
    public interface ResolveSyscall extends CFunctionPointer { @InvokeCFunctionPointer int resolve(CCharPointer name); }
    
    @CFunction public static native long syscall(long number, long a, long b, long c, long d, long e, long f);
    @CFunction public static native int mknod(CCharPointer path, int mode, UnsignedWord device);
    @CFunction("gnu_dev_makedev") public static native UnsignedWord device(int major, int minor);
    @CFunction("gnu_dev_major") public static native int major(UnsignedWord device);
    @CFunction("gnu_dev_minor") public static native int minor(UnsignedWord device);
    @CFunction public static native int geteuid();
    @CFunction public static native int getgroups(int count, CIntPointer groups);
    @CFunction public static native int isatty(int fd);
    @CFunction public static native CIntPointer __errno_location();
    @CFunction public static native CCharPointer strerror(int error);
    @CFunction public static native int openat(int parent, CCharPointer path, int flags, int mode);
    @CFunction public static native int mkdirat(int parent, CCharPointer name, int mode);
    @CFunction public static native int close(int fd);
    @CFunction public static native int fchmod(int fd, int mode);
    @CFunction public static native int flock(int fd, int operation);
    @CFunction public static native int pipe2(CIntPointer pair, int flags);
    @CFunction public static native long read(int fd, CCharPointer bytes, UnsignedWord count);
    @CFunction public static native long write(int fd, CCharPointer bytes, UnsignedWord count);
    @CFunction public static native int fstat(int fd, Stat stat);
    @CFunction public static native int access(CCharPointer path, int mode);
    @CFunction public static native int poll(PollFd fd, UnsignedWord count, int timeout);
    @CFunction public static native int kill(int pid, int signal);
    @CFunction public static native int waitpid(int pid, CIntPointer status, int options);
    @CFunction
    public static native int posix_spawn(
        CIntPointer pid, CCharPointer path, Actions actions, PointerBase attrs, CCharPointerPointer argv, CCharPointerPointer env
    );
    @CFunction public static native int posix_spawn_file_actions_init(Actions actions);
    @CFunction public static native int posix_spawn_file_actions_destroy(Actions actions);
    @CFunction public static native int posix_spawn_file_actions_adddup2(Actions actions, int from, int to);
    @CFunction public static native int posix_spawn_file_actions_addclose(Actions actions, int fd);
    @CStruct(value="posix_spawn_file_actions_t", addStructKeyword=false) interface Actions extends PointerBase {}
    @CStruct(value="stat", addStructKeyword=true) interface Stat extends PointerBase {
        @CField("st_dev") long dev(); @CField("st_ino") long ino(); @CField("st_mode") int mode();
    }
    
    @CStruct(value="pollfd", addStructKeyword=true) interface PollFd extends PointerBase {
        @CField void fd(int value); @CField void events(short value); @CField short revents();
    }
    
    @CFunction public static native int socket(int domain, int type, int protocol);
    @CFunction public static native int socketpair(int domain, int type, int protocol, CIntPointer pair);
    @CFunction public static native int bind(int fd, Sockaddr address, int length);
    @CFunction public static native int connect(int fd, Sockaddr address, int length);
    @CFunction public static native int listen(int fd, int backlog);
    @CFunction public static native int accept4(int fd, PointerBase address, PointerBase length, int flags);
    @CFunction public static native int getsockopt(int fd, int level, int option, CIntPointer value, CIntPointer length);
    @CFunction public static native long send(int fd, CCharPointer data, UnsignedWord length, int flags);
    @CConstant public static native int AF_UNIX();
    @CConstant public static native int SOCK_SEQPACKET();
    @CConstant public static native int SOCK_STREAM();
    @CConstant public static native int SOCK_DGRAM();
    @CConstant public static native int SOCK_CLOEXEC();
    @CConstant public static native int SOCK_NONBLOCK();
    @CConstant public static native int SOL_SOCKET();
    @CConstant public static native int SO_ERROR();
    @CConstant public static native int MSG_NOSIGNAL();
    
    @CStruct(value="sockaddr_un", addStructKeyword=true) public interface Sockaddr extends PointerBase {
        @CField("sun_family") void family(short family);
        @CFieldAddress("sun_path") CCharPointer path();
    }
    
    @FunctionalInterface private interface SocketAddressAction { int call(Sockaddr address, int length); }
    
    private static int address(String name, SocketAddressAction action) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8); boolean abstractName = name.startsWith("@");
        if (abstractName) bytes[0] = 0;
        if (bytes.length >= 108 || (!abstractName && name.indexOf(0) >= 0)) throw new IOException("Invalid UNIX socket path");
        Sockaddr address = UnmanagedMemory.calloc(SizeOf.get(Sockaddr.class));
        try {
            address.family((short) AF_UNIX()); for (int i = 0; i < bytes.length; i++) address.path().write(i, bytes[i]);
            int offset = (int) (address.path().rawValue() - address.rawValue());
            return action.call(address, offset + bytes.length + (abstractName ? 0 : 1));
        } finally { UnmanagedMemory.free(address); }
    }
    
    public static Unix[] socketPair() throws IOException {
        CIntPointer pair = UnmanagedMemory.malloc(8);
        try {
            check(socketpair(AF_UNIX(), SOCK_STREAM() | SOCK_CLOEXEC() | SOCK_NONBLOCK(), 0, pair), "socketpair");
            return new Unix[]{new Unix(new Fd(pair.read())), new Unix(new Fd(pair.read(1)))};
        } finally { UnmanagedMemory.free(pair); }
    }
    
    public record Unix(Fd fd) implements AutoCloseable {
        public static Unix server(String path, int type) throws IOException {
            Unix socket = new Unix(new Fd(check(Posix.socket(AF_UNIX(), type | SOCK_CLOEXEC() | SOCK_NONBLOCK(), 0), "socket")));
            try { check(address(path, (p, n) -> bind(socket.fd.n, p, n)), "bind"); check(listen(socket.fd.n, 8), "listen"); return socket; }
            catch (IOException e) { socket.close(); throw e; }
        }
        
        public static Unix connect(String path, int type, int timeout) throws IOException {
            Unix socket = new Unix(new Fd(check(Posix.socket(AF_UNIX(), type | SOCK_CLOEXEC() | SOCK_NONBLOCK(), 0), "socket")));
            try {
                int result = address(path, (p, n) -> Posix.connect(socket.fd.n, p, n));
                if (result < 0) {
                    if (!Set.of(11, 115).contains(errno())) throw error("connect");
                    if (!socket.fd.ready((short) 4, timeout)) throw new SocketTimeoutException("Socket connect timed out");
                    CIntPointer option = UnmanagedMemory.malloc(4), length = UnmanagedMemory.malloc(4);
                    try {
                        length.write(4);
                        check(getsockopt(socket.fd.n, SOL_SOCKET(), SO_ERROR(), option, length), "getsockopt");
                        if (option.read() != 0) throw new Failure("connect", option.read());
                    } finally { UnmanagedMemory.free(option); UnmanagedMemory.free(length); }
                }
                return socket;
            } catch (IOException e) { socket.close(); throw e; }
        }
        
        public @Nullable Unix accept(int timeout) throws IOException {
            if (!fd.ready((short) 1, timeout)) return null;
            int client = accept4(fd.n, WordFactory.nullPointer(), WordFactory.nullPointer(), SOCK_CLOEXEC() | SOCK_NONBLOCK());
            if (client < 0 && errno() == 11) return null;
            return new Unix(new Fd(check(client, "accept")));
        }
        
        public void send(byte[] bytes, int timeout) throws IOException {
            if (!fd.ready((short) 4, timeout)) throw new SocketTimeoutException("Socket send timed out");
            try (var data = toCBytes(bytes)) {
                long n = Posix.send(fd.n, data.get(), WordFactory.unsigned(bytes.length), MSG_NOSIGNAL());
                if (n < 0) throw error("send");
                if (n != bytes.length) throw new IOException("Short socket packet");
            }
        }
        
        /** Stream writes may be partial; one deadline covers the whole message. */
        public void sendAll(byte[] bytes, int timeout) throws IOException {
            long deadline = System.nanoTime() + timeout * 1_000_000L;
            try (var data = toCBytes(bytes)) {
                for (int offset = 0; offset < bytes.length;) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !fd.ready((short) 4, Math.max(1, (int) (remaining / 1_000_000))))
                        throw new SocketTimeoutException("Socket send timed out");
                    long n = Posix.send(fd.n, data.get().addressOf(offset), WordFactory.unsigned(bytes.length - offset), MSG_NOSIGNAL());
                    if (n < 0) { if (errno() == 4 || errno() == 11) continue; throw error("send"); }
                    if (n == 0) throw new EOFException("Socket closed during send");
                    offset += (int) n;
                }
            }
        }
        
        public byte[] receive(int count, int timeout) throws IOException { return fd.read(count, timeout); }
        
        public void close() { fd.close(); }
    }
    
    public static int errno() { return __errno_location().read(); }
    
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final int errno;
        Failure(String operation, int errno) { super(operation + ": " + toJavaString(strerror(errno))); this.errno = errno; }
    }
    
    public static IOException error(String operation) { return new Failure(operation, errno()); }
    
    public static int check(int result, String operation) throws IOException { if (result < 0) throw error(operation); return result; }
    
    public static Fd open(String path, int flags) throws IOException { return open(AT_FDCWD(), path, flags, 0600); }
    
    public static Fd open(int parent, String path, int flags, int mode) throws IOException {
        if (path.indexOf(0) >= 0) throw new IOException("NUL in path");
        try (var p = toCString(path)) { return new Fd(check(openat(parent, p.get(), flags | O_CLOEXEC(), mode), "open " + path)); }
    }
    
    public static Fd[] pipe() throws IOException {
        CIntPointer pair = UnmanagedMemory.malloc(8);
        try {
            check(pipe2(pair, O_CLOEXEC()), "pipe");
            return new Fd[]{new Fd(pair.read()), new Fd(pair.read(1))};
        } finally { UnmanagedMemory.free(pair); }
    }
    
    public static final class Fd implements AutoCloseable {
        public final int n; private boolean closed;
        public Fd(int n) { this.n = n; }
        
        public String path() { return "/proc/self/fd/" + n; }
        
        public void lock(boolean nonblocking) throws IOException { check(flock(n, 2 | (nonblocking ? 4 : 0)), "flock"); }
        
        public boolean ready(short events, int timeout) throws IOException {
            PollFd poll = UnmanagedMemory.calloc(SizeOf.get(PollFd.class));
            try {
                poll.fd(n);
                poll.events(events);
                int result;
                do { result = Posix.poll(poll, WordFactory.unsigned(1), timeout); } while (result < 0 && errno() == 4);
                check(result, "poll"); return result != 0;
            } finally { UnmanagedMemory.free(poll); }
        }
        
        public byte[] read(int count, int timeout) throws IOException {
            if (!ready((short) 1, timeout)) throw new SocketTimeoutException("Descriptor readiness timed out");
            CCharPointer buffer = UnmanagedMemory.malloc(count);
            try {
                long size;
                do { size = Posix.read(n, buffer, WordFactory.unsigned(count)); } while (size < 0 && errno() == 4);
                if (size < 0) throw error("read");
                byte[] bytes = new byte[(int) size];
                for (int i = 0; i < bytes.length; i++) bytes[i] = buffer.read(i);
                return bytes;
            } finally { UnmanagedMemory.free(buffer); }
        }
        
        public byte[] readAll() throws IOException {
            var out = new ByteArrayOutputStream(); byte[] bytes;
            while ((bytes = read(8192, -1)).length != 0) out.write(bytes);
            return out.toByteArray();
        }
        
        /** A finite pipe document ends at EOF, not at an arbitrary read boundary. */
        public byte[] readAll(int limit, int timeout) throws IOException {
            Json.require(limit >= 0 && timeout > 0, "Invalid bounded descriptor read");
            var out = new ByteArrayOutputStream(); long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            for (;;) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("Descriptor document timed out");
                byte[] bytes = read(8192, (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (bytes.length == 0) return out.toByteArray();
                Json.require(bytes.length <= limit - out.size(), "Oversized descriptor document"); out.writeBytes(bytes);
            }
        }
        
        public void write(byte[] bytes) throws IOException {
            try (var p = toCBytes(bytes)) {
                for (int i = 0; i < bytes.length;) {
                    long count = Posix.write(n, p.get().addressOf(i), WordFactory.unsigned(bytes.length - i));
                    if (count < 0) { if (errno() == 4) continue; throw error("write"); }
                    if (count == 0) throw new IOException("Short descriptor write");
                    i += (int) count;
                }
            }
        }
        
        public void close() { if (!closed) { Posix.close(n); closed = true; } }
    }
    
    public record Inode(long dev, long ino, int mode) {}
    
    public static Inode stat(int fd) throws IOException {
        Stat s = UnmanagedMemory.malloc(SizeOf.get(Stat.class));
        try { check(fstat(fd, s), "fstat"); return new Inode(s.dev(), s.ino(), s.mode()); }
        finally { UnmanagedMemory.free(s); }
    }
    
    public static boolean access(String path, int mode) { try (var p = toCString(path)) { return access(p.get(), mode) == 0; } }
    
    public static final class Child implements AutoCloseable {
        public final int pid; private @Nullable Integer code;
        Child(int pid) { this.pid = pid; }
        
        public synchronized @Nullable Integer poll() throws IOException {
            if (code != null) return code;
            CIntPointer status = UnmanagedMemory.malloc(4);
            try {
                int r = waitpid(pid, status, 1);
                if (r < 0 && errno() == 4) return null;
                check(r, "waitpid");
                if (r != 0) { int s = status.read(); code = (s & 127) == 0 ? (s >>> 8) & 255 : -(s & 127); }
                return code;
            } finally { UnmanagedMemory.free(status); }
        }
        
        public int waitFor() throws IOException, InterruptedException {
            for (;;) {
                Integer result = poll();
                if (result != null) return result;
                Thread.sleep(10);
            }
        }
        
        public void close() throws IOException, InterruptedException {
            if (poll() != null) return;
            kill(pid, 15);
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (poll() == null && System.nanoTime() < deadline) Thread.sleep(10);
            if (poll() == null) { kill(pid, 9); waitFor(); }
        }
    }
    
    /** posix_spawn file actions preserve only the explicitly granted descriptors. */
    public static Child spawn(List<String> command, Map<String, String> env, Collection<Fd> keep, int input, int output, int error) throws IOException {
        Actions actions = UnmanagedMemory.malloc(SizeOf.get(Actions.class));
        CIntPointer pid = UnmanagedMemory.malloc(4);
        int initialized = posix_spawn_file_actions_init(actions);
        try {
            if (initialized != 0) throw new IOException("spawn actions: " + initialized);
            Map<Integer, Integer> copies = new LinkedHashMap<>(Map.of(0, input, 1, output, 2, error));
            for (var copy : copies.entrySet()) {
                if (posix_spawn_file_actions_adddup2(actions, copy.getValue(), copy.getKey()) != 0)
                    throw new IOException("spawn dup2 failed");
            }
            Set<Integer> retained = new HashSet<>(List.of(0, 1, 2));
            for (Fd fd : keep) {
                retained.add(fd.n);
                if (posix_spawn_file_actions_adddup2(actions, fd.n, fd.n) != 0) throw new IOException("spawn pass-fd failed");
            }
            List<Integer> open;
            try (var files = Files.list(Path.of("/proc/self/fd"))) {
                open = files.map(p -> Integer.parseInt(Fs.name(p))).filter(n -> !retained.contains(n)).toList();
            }
            for (int fd : open) if (posix_spawn_file_actions_addclose(actions, fd) != 0) throw new IOException("spawn close-fd failed");
            String[] environment = env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
            for (String s : command) if (s.indexOf(0) >= 0) throw new IOException("NUL in argument");
            for (String s : environment) if (s.indexOf(0) >= 0) throw new IOException("NUL in environment");
            try (var path = toCString(command.getFirst()); var argv = toCStrings(command.toArray(String[]::new)); var vars = toCStrings(environment)) {
                int result = posix_spawn(pid, path.get(), actions, WordFactory.nullPointer(), argv.get(), vars.get());
                if (result != 0) throw new IOException("spawn " + command.getFirst() + ": " + toJavaString(strerror(result)));
                return new Child(pid.read());
            }
        } finally {
            if (initialized == 0) posix_spawn_file_actions_destroy(actions);
            UnmanagedMemory.free(actions);
            UnmanagedMemory.free(pid);
        }
    }
    
}