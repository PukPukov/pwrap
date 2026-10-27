package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Small bounded test peer, only the D-Bus types used by our public-marker fixtures. */
final class FixtureBus implements AutoCloseable {
    
    record Message(int type, int serial, int reply, Map<Integer, String> fields, byte[] body, ByteOrder order) {
        String field(int code) { return fields.getOrDefault(code, ""); }

        String firstString() { return new Reader(body, order).string(); }
    }

    static final class Writer extends ByteArrayOutputStream {
        Writer align(int alignment) {
            while (size() % alignment != 0) write(0);
            return this;
        }

        Writer uint(int value) {
            align(4);
            for (int n = 0; n < 4; n++) write(value >>> (8 * n) & 255);
            return this;
        }

        Writer string(String text) {
            byte[] value = text.getBytes(StandardCharsets.UTF_8);
            align(4).uint(value.length);
            writeBytes(value);
            write(0);
            return this;
        }

        Writer signature(String signature) {
            byte[] value = signature.getBytes(StandardCharsets.US_ASCII);
            require(value.length <= 255, "Fixture signature too long");
            write(value.length);
            writeBytes(value);
            write(0);
            return this;
        }

        Writer field(int code, char type, String value) {
            align(8);
            write(code);
            signature("" + type);
            if (type == 'g') signature(value); else string(value);
            return this;
        }

        Writer integerField(int code, int value) {
            align(8);
            write(code);
            signature("u");
            align(4).uint(value);
            return this;
        }
    }

    static final class Reader {
        final ByteBuffer data;

        Reader(byte[] bytes, ByteOrder order) { data = ByteBuffer.wrap(bytes).order(order); }

        void align(int alignment) {
            int offset = (data.position() + alignment - 1) / alignment * alignment;
            require(offset <= data.limit(), "Invalid fixture message padding");
            data.position(offset);
        }

        String string() {
            align(4);
            int length = data.getInt();
            require(length >= 0 && length < data.remaining(), "Invalid fixture string");
            byte[] text = new byte[length];
            data.get(text);
            require(data.get() == 0, "Missing string terminator");
            return new String(text, StandardCharsets.UTF_8);
        }

        String signature() {
            int length = data.get() & 255;
            require(length < data.remaining(), "Invalid fixture signature");
            byte[] text = new byte[length];
            data.get(text);
            require(data.get() == 0, "Missing signature terminator");
            return new String(text, StandardCharsets.US_ASCII);
        }

        List<String> strings() {
            align(4);
            int length = data.getInt(), end = data.position() + length;
            require(length >= 0 && end <= data.limit(), "Invalid fixture string array");
            var found = new ArrayList<String>();
            while (data.position() < end) found.add(string());
            require(data.position() == end, "Invalid string array end");
            return found;
        }
    }

    private final Posix.Unix socket;
    private int serial;
    private String unique = "";
    final List<Message> signals = new ArrayList<>();

    String unique() {
        require(unique.startsWith(":"), "Fixture Hello not complete");
        return unique;
    }

    FixtureBus(String address) throws IOException {
        require(address.startsWith("unix:path=") && !address.contains(";") && !address.contains(","), "Fixture requires one filesystem bus socket");
        socket = Posix.Unix.connect(address.substring(10), Posix.SOCK_STREAM(), 5000);
        try {
            String uid = HexFormat.of().formatHex(Integer.toString(Posix.getuid()).getBytes(StandardCharsets.US_ASCII));
            socket.sendAll(("\0AUTH EXTERNAL " + uid + "\r\n").getBytes(StandardCharsets.US_ASCII), 5000);
            var line = new ByteArrayOutputStream();
            while (line.size() < 1024) {
                byte[] part = exact(1);
                line.writeBytes(part);
                if (part[0] == '\n') break;
            }
            require(line.toString(StandardCharsets.US_ASCII).startsWith("OK "), "Fixture bus auth failed");
            socket.sendAll("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII), 5000);
            Message hello = call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "", new Writer());
            require(hello.type == 2, "Fixture Hello failed");
            unique = hello.firstString();
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    private byte[] exact(int count) throws IOException {
        var bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bytes.size() < count) {
            require(System.nanoTime() < deadline, "Fixture bus frame timed out");
            byte[] part = socket.receive(count - bytes.size(), 5000);
            if (part.length == 0) throw new EOFException("Fixture bus disconnected");
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    Message read() throws IOException {
        byte[] fixed = exact(16);
        require((fixed[0] == 'l' || fixed[0] == 'B') && fixed[3] == 1, "Invalid fixture bus header");
        ByteOrder order = fixed[0] == 'l' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer header = ByteBuffer.wrap(fixed).order(order);
        int size = header.getInt(4), length = header.getInt(12);
        require(size >= 0 && size <= 1_048_576 && length >= 0 && length <= 65536, "Oversized fixture bus frame");
        var fields = new LinkedHashMap<Integer, String>();
        var values = new Reader(exact(length), order);
        int reply = 0;
        while (values.data.hasRemaining()) {
            values.align(8);
            if (!values.data.hasRemaining()) break;
            int code = values.data.get() & 255;
            String type = values.signature();
            if (type.equals("u")) {
                values.align(4);
                int value = values.data.getInt();
                if (code == 5) reply = value;
            } else if (type.equals("g")) fields.put(code, values.signature());
            else {
                require(type.equals("s") || type.equals("o"), "Unsupported fixture header type");
                fields.put(code, values.string());
            }
        }
        exact((8 - (16 + length) % 8) % 8);
        return new Message(fixed[1], header.getInt(8), reply, Map.copyOf(fields), exact(size), order);
    }

    private synchronized int send(int type, Writer fields, String signature, Writer body) throws IOException {
        if (!signature.isEmpty()) fields.field(8, 'g', signature);
        int request = ++serial;
        Writer message = new Writer();
        message.writeBytes(new byte[]{'l', (byte) type, 0, 1});
        message.uint(body.size()).uint(request).uint(fields.size());
        message.writeBytes(fields.toByteArray());
        message.align(8);
        message.writeBytes(body.toByteArray());
        socket.sendAll(message.toByteArray(), 5000);
        return request;
    }

    Message call(String destination, String path, String iface, String member, String signature, Writer body) throws IOException {
        int request = send(1, new Writer().field(1, 'o', path).field(2, 's', iface).field(3, 's', member).field(6, 's', destination), signature, body);
        for (int count = 0; count < 128; count++) {
            Message message = read();
            if (message.reply == request) return message;
            if (message.type == 4) signals.add(message);
        }
        throw new IOException("Fixture call exceeded signal budget");
    }

    void own(String name) throws IOException {
        require(
            call(
                "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su",
                new Writer().string(name).uint(0)
            ).type == 2,
            "Fixture name acquisition failed"
        );
    }

    void reply(Message request, List<String> strings) throws IOException {
        Writer body = new Writer();
        strings.forEach(body::string);
        send(
            2, new Writer().integerField(5, request.serial).field(6, 's', request.field(7)),
            "s".repeat(strings.size()), body
        );
    }

    void signal(String destination, String path, String iface, String member) throws IOException {
        Writer fields = new Writer().field(1, 'o', path).field(2, 's', iface).field(3, 's', member);
        if (!destination.isEmpty()) fields.field(6, 's', destination);
        send(4, fields, "", new Writer());
    }

    boolean readable(int timeout) throws IOException { return socket.fd().ready((short) 1, timeout); }

    void drain() throws IOException {
        for (int count = 0; readable(100); count++) {
            require(count < 128, "Fixture drain exceeded signal budget");
            Message message = read();
            if (message.type == 4) signals.add(message);
        }
    }

    public void close() { socket.close(); }
    
}