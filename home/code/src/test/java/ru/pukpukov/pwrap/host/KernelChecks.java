package ru.pukpukov.pwrap.host;

import module java.base;
import module org.graalvm.nativeimage;
import module org.graalvm.word;
import ru.pukpukov.pwrap.core.Posix;
import static ru.pukpukov.pwrap.core.Json.require;

/** Policy tests only: offline BPF evaluation and invalid socket types, never exotic sockets. */
@CContext(KernelChecks.Headers.class)
public final class KernelChecks {
    
    public static final class Headers implements CContext.Directives {
        
        public List<String> getHeaderFiles() { return List.of("<sys/socket.h>", "<sys/syscall.h>", "<unistd.h>", "<errno.h>"); }
    }
    
    @CConstant("SYS_socket") static native int SOCKET();
    @CConstant("SYS_socketpair") static native int SOCKETPAIR();
    @CConstant("SYS_bpf") static native int BPF();
    @CConstant("SYS_clone3") static native int CLONE3();
    @CConstant static native int EAFNOSUPPORT();
    @CConstant static native int EINVAL();
    @CFunction("syscall") static native long socketCall(int number, UnsignedWord domain, int type, int protocol, VoidPointer pair);
    
    private static boolean allowed(int family) { return Set.of(1, 2, 10, 16).contains(family); }
    
    private static List<Integer> domains() {
        var values = new ArrayList<Integer>();
        for (int value = -2; value <= 128; value++) values.add(value);
        values.addAll(List.of(65535, Integer.MAX_VALUE, Integer.MIN_VALUE));
        return values;
    }
    
    private static long[] highBits() { return new long[]{0, 1L << 32, 0x12345678L << 32, Long.MIN_VALUE, 0xffffffffL << 32}; }
    
    static void socketArguments() {
        for (int number : new int[]{SOCKET(), SOCKETPAIR()}) for (int domain : domains()) for (long high : highBits()) {
            // type=-1 is rejected before protocol creation; pair pointer is never used.
            long result = socketCall(number, WordFactory.unsigned(high | Integer.toUnsignedLong(domain)), -1, 0, WordFactory.nullPointer());
            int error = Posix.errno();
            require(
                result == -1 && error == (allowed(domain) ? EINVAL() : EAFNOSUPPORT()),
                "Socket family filter changed: syscall=" + number + " domain=" + domain + " errno=" + error
            );
        }
        System.out.println("PASS: socket/socketpair closed allowlist, future/negative domains and ignored register high bits (invalid types only)");
    }

    public static void filter(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file); require(bytes.length > 0 && bytes.length % 8 == 0, "Invalid seccomp program size");
        require(System.getProperty("os.arch").equals("amd64"), "Current packaging/filter verification supports x86_64 only");
        for (int number : new int[]{SOCKET(), SOCKETPAIR()}) for (int domain : domains()) for (long high : highBits())
            require(
                evaluate(bytes, number, 0xc000003e, high | Integer.toUnsignedLong(domain), -1) ==
                    (allowed(domain) ? 0x7fff0000L : 0x00050000L | EAFNOSUPPORT()),
                "Offline family BPF result differs from declared allowlist"
            );
        for (int architecture : new int[]{0x40000003, 0xc00000b7, 0})
            require(killed(evaluate(bytes, SOCKET(), architecture, 1, 0)), "Foreign socket ABI admitted");
        require(killed(evaluate(bytes, SOCKET() | 0x40000000, 0xc000003e, 1, 0)), "x32 socket ABI admitted");
        require(
            evaluate(bytes, BPF(), 0xc000003e, 0, 0) == 0x00050001L &&
                evaluate(bytes, CLONE3(), 0xc000003e, 0, 0) == 0x00050026L,
            "Family guard skipped original privileged-syscall rules"
        );
        System.out.println("PASS: exported BPF family/ABI allowlist and original syscall rules (offline, no foreign syscalls executed)");
    }

    private static boolean killed(long action) { return (action & 0xffff0000L) == 0 || (action & 0xffff0000L) == 0x80000000L; }
    
    /** Only the forward classic-BPF subset emitted by this generator/libseccomp. */
    static long evaluate(byte[] program, int number, int architecture, long first, long second) {
        ByteBuffer code = ByteBuffer.wrap(program).order(ByteOrder.nativeOrder());
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        data.putInt(0, number).putInt(4, architecture).putLong(16, first).putLong(24, second);
        long accumulator = 0;
        for (int pc = 0, steps = 0; pc >= 0 && pc < program.length / 8 && steps < program.length; steps++) {
            int offset = pc * 8, operation = code.getShort(offset) & 65535;
            int yes = code.get(offset + 2) & 255, no = code.get(offset + 3) & 255;
            long value = Integer.toUnsignedLong(code.getInt(offset + 4));
            pc++;
            switch (operation) {
                case 0x20 -> {
                    require(value <= data.capacity() - 4, "Invalid BPF data offset");
                    accumulator = Integer.toUnsignedLong(data.getInt((int) value));
                }
                case 0x15 -> pc += accumulator == value ? yes : no;
                case 0x25 -> pc += accumulator > value ? yes : no;
                case 0x35 -> pc += accumulator >= value ? yes : no;
                case 0x45 -> pc += (accumulator & value) != 0 ? yes : no;
                case 0x54 -> accumulator &= value;
                case 0x05 -> { require(value < program.length / 8, "Invalid BPF jump"); pc += (int) value; }
                case 0x06 -> { return value; }
                default -> throw new IllegalArgumentException("Unsupported fixture BPF opcode: " + operation);
            }
        }
        throw new IllegalArgumentException("BPF program did not return");
    }
}