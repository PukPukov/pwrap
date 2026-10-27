package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.Posix;
import static ru.pukpukov.pwrap.core.Json.*;

/** Small bounded D-Bus client for the startup audit: EXTERNAL auth, method calls, replies. */
final class Bus implements AutoCloseable {
    
    private final Posix.Unix socket;
    private int serial;
    
    record Reply(int type, String error, String signature, ByteOrder order, byte[] body) {
        String firstString() {
            require(signature.startsWith("s"), "Expected D-Bus string reply");
            return new Reader(body, order).string();
        }
    }
    
    Bus(String address) throws IOException {
        require(address.startsWith("unix:path=") && !address.contains(",") && !address.contains(";"), "Expected one local D-Bus socket");
        socket = Posix.Unix.connect(address.substring(10), Posix.SOCK_STREAM(), 12000);
        try {
            String uid = HexFormat.of().formatHex(Integer.toString(Posix.getuid()).getBytes(StandardCharsets.US_ASCII));
            socket.sendAll(("\0AUTH EXTERNAL " + uid + "\r\n").getBytes(StandardCharsets.US_ASCII), 12000);
            var line = new ByteArrayOutputStream();
            long deadline = deadline();
            while (line.size() < 1024) {
                byte[] b = exact(1, deadline);
                line.write(b);
                if (b[0] == '\n') break;
            }
            require(line.toString(StandardCharsets.US_ASCII).startsWith("OK "), "D-Bus external authentication failed");
            socket.sendAll("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII), 12000);
            require(call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.Hello", "", List.of()).type == 2, "D-Bus Hello failed");
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
    
    private static long deadline() { return System.nanoTime() + 12_000_000_000L; }
    
    private byte[] exact(int count, long deadline) throws IOException {
        var out = new ByteArrayOutputStream();
        while (out.size() < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("D-Bus call timed out");
            byte[] bytes = socket.receive(count - out.size(), Math.max(1, (int) (remaining / 1_000_000)));
            if (bytes.length == 0) throw new EOFException("D-Bus connection closed");
            out.write(bytes);
        }
        return out.toByteArray();
    }
    
    private static final class Writer extends ByteArrayOutputStream {
        void align(int n) { while (size() % n != 0) write(0); }
        void uint(int value) { for (int i = 0; i < 4; i++) write(value >>> (8 * i) & 255); }
    
        void string(String value) {
            align(4);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            uint(bytes.length);
            writeBytes(bytes);
            write(0);
        }
    
        void signature(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            require(bytes.length <= 255, "Oversized D-Bus signature");
            write(bytes.length);
            writeBytes(bytes);
            write(0);
        }
    
        void field(int code, char type, String value) {
            align(8);
            write(code);
            signature("" + type);
            if (type == 'g') signature(value); else string(value);
        }
    }
    
    private static final class Reader {
        final ByteBuffer bytes;
        Reader(byte[] value, ByteOrder order) { bytes = ByteBuffer.wrap(value).order(order); }
        void align(int n) { bytes.position((bytes.position() + n - 1) / n * n); }
    
        String string() {
            align(4);
            int n = bytes.getInt();
            require(n >= 0 && n < bytes.remaining(), "Invalid D-Bus string length");
            byte[] text = new byte[n];
            bytes.get(text);
            require(bytes.get() == 0, "Invalid D-Bus string terminator");
            return new String(text, StandardCharsets.UTF_8);
        }
    
        String signature() {
            int n = bytes.get() & 255;
            require(n < bytes.remaining(), "Invalid D-Bus signature length");
            byte[] text = new byte[n];
            bytes.get(text);
            require(bytes.get() == 0, "Invalid D-Bus signature terminator");
            return new String(text, StandardCharsets.US_ASCII);
        }
    }
    
    Reply call(String destination, String path, String method, String signature, List<String> arguments) throws IOException {
        require(signature.equals("s".repeat(arguments.size())), "Startup bus client supports string arguments only");
        int dot = method.lastIndexOf('.');
        require(dot > 0, "Expected qualified D-Bus method");
        Writer fields = new Writer(), body = new Writer();
        fields.field(1, 'o', path);
        fields.field(2, 's', method.substring(0, dot));
        fields.field(3, 's', method.substring(dot + 1));
        fields.field(6, 's', destination);
        if (!signature.isEmpty()) fields.field(8, 'g', signature);
        arguments.forEach(body::string);
        int request = ++serial;
        Writer message = new Writer();
        message.writeBytes(new byte[]{'l', 1, 0, 1});
        message.uint(body.size());
        message.uint(request);
        message.uint(fields.size());
        message.writeBytes(fields.toByteArray());
        message.align(8);
        message.writeBytes(body.toByteArray());
        socket.sendAll(message.toByteArray(), 12000);
        long deadline = deadline();
        for (int ignored = 0; ignored < 64; ignored++) {
            byte[] fixed = exact(16, deadline);
            require(fixed[0] == 'l' || fixed[0] == 'B', "Invalid D-Bus byte order");
            ByteOrder order = fixed[0] == 'l' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            ByteBuffer header = ByteBuffer.wrap(fixed).order(order);
            int length = header.getInt(4), headerLength = header.getInt(12);
            require(
                fixed[3] == 1 && length >= 0 && length <= 1_048_576 && headerLength >= 0 && headerLength <= 65536,
                "Invalid D-Bus message size/version"
            );
            Reader values = new Reader(exact(headerLength, deadline), order);
            String error = "", bodySignature = "";
            int reply = 0;
            while (values.bytes.hasRemaining()) {
                values.align(8);
                if (!values.bytes.hasRemaining()) break;
                int code = values.bytes.get() & 255;
                String type = values.signature();
                if (type.equals("u")) {
                    values.align(4);
                    int value = values.bytes.getInt();
                    if (code == 5) reply = value;
                } else if (type.equals("g")) {
                    String value = values.signature();
                    if (code == 8) bodySignature = value;
                } else {
                    require(type.equals("s") || type.equals("o"), "Unexpected D-Bus header variant");
                    String value = values.string();
                    if (code == 4) error = value;
                }
            }
            exact((8 - (16 + headerLength) % 8) % 8, deadline);
            byte[] payload = exact(length, deadline);
            if (reply == request) return new Reply(fixed[1], error, bodySignature, order, payload);
        }
        throw new IOException("D-Bus reply not received within bounded signal budget");
    }
    
    public void close() { socket.close(); }
}