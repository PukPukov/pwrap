package ru.pukpukov.pwrap.host;

import module java.base;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;
import static ru.pukpukov.pwrap.host.HostChecks.*;
import ru.pukpukov.pwrap.host.HostChecks.Runtime;

/** Registry/bind contract only: no surfaces, clipboard reads, input or capture. */
final class HostWayland {
    
    private static final Set<String> FORBIDDEN = Set.of(
        "ext_data_control_manager_v1", "zwlr_data_control_manager_v1", "org_kde_kwin_fake_input",
        "org_kde_plasma_window_management", "zkde_screencast_unstable_v1", "zwlr_screencopy_manager_v1",
        "ext_image_copy_capture_manager_v1", "wp_security_context_manager_v1", "zwp_virtual_keyboard_manager_v1",
        "zwlr_virtual_pointer_manager_v1", "xwayland_shell_v1", "ext_foreign_toplevel_list_v1"
    );
    
    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()); }
    
    private static byte[] message(int object, int opcode, byte[] body) {
        return bytes(8 + body.length).putInt(object).putInt((8 + body.length) << 16 | opcode).put(body).array();
    }
    
    private static Posix.Unix connect() throws IOException {
        String display = required(System.getenv("WAYLAND_DISPLAY"), "Missing Wayland session");
        String root = required(System.getenv("XDG_RUNTIME_DIR"), "Missing Wayland runtime directory");
        return Posix.Unix.connect(display.startsWith("/") ? display : Path.of(root, display).toString(), Posix.SOCK_STREAM(), 5000);
    }
    
    private static byte[] exact(Posix.Unix socket, int count) throws IOException {
        var data = new ByteArrayOutputStream();
        while (data.size() < count) {
            byte[] part = socket.receive(count - data.size(), 5000);
            require(part.length > 0, "Compositor/proxy disconnected before sync");
            data.writeBytes(part);
        }
        return data.toByteArray();
    }
    
    private static Map<String, Integer> registry(Posix.Unix socket) throws IOException {
        socket.sendAll(message(1, 1, bytes(4).putInt(2).array()), 5000);
        socket.sendAll(message(1, 0, bytes(4).putInt(3).array()), 5000);
        var globals = new TreeMap<String, Integer>();
        boolean done = false, deleted = false;
        for (int count = 0; count < 4096; count++) {
            ByteBuffer header = ByteBuffer.wrap(exact(socket, 8)).order(ByteOrder.nativeOrder());
            int object = header.getInt(), word = header.getInt(), size = word >>> 16, opcode = word & 65535;
            require(size >= 8 && size % 4 == 0, "Invalid Wayland frame");
            ByteBuffer body = ByteBuffer.wrap(exact(socket, size - 8)).order(ByteOrder.nativeOrder());
            if (object == 2 && opcode == 0) {
                require(body.remaining() >= 12, "Short registry frame");
                int name = body.getInt(), length = body.getInt();
                require(length > 0 && length <= body.remaining() - 4, "Invalid interface length");
                byte[] text = new byte[length];
                body.get(text);
                require(text[length - 1] == 0, "Invalid interface terminator");
                globals.put(new String(text, 0, length - 1, StandardCharsets.UTF_8), name);
            }
            if (object == 3 && opcode == 0) done = true;
            if (object == 1 && opcode == 1 && body.remaining() == 4 && body.getInt() == 3) deleted = true;
            if (done && deleted) return globals;
        }
        throw new IOException("Registry sync exceeded frame budget");
    }
    
    static void check(Runtime runtime, Path payload, Path work) throws Exception {
        if (System.getenv("WAYLAND_DISPLAY") == null || System.getenv("XDG_RUNTIME_DIR") == null) {
            System.out.println("SKIP: Wayland registry (no captured session)");
            return;
        }
        Map<String, Integer> upstream;
        try (var socket = connect()) { upstream = registry(socket); }
        var blocked = upstream.keySet().stream().filter(FORBIDDEN::contains).sorted().toList();
        if (blocked.isEmpty()) {
            System.out.println("SKIP: Wayland hidden-bind check (no sensitive upstream global)");
            return;
        }
        String interfaceName = blocked.getFirst();
        var policy = obj(
            "desktop", true, "gpu", false, "command",
            List.of(
                payload.toString(), "_payload", "WAYLAND",
                interfaceName, Integer.toString(required(upstream.get(interfaceName), "Missing fixture global"))
            )
        );
        Path manifest = write(work.resolve("profile.json"), policy.toString());
        require(
            run(work, "registry", runtime.command(List.of("profile", manifest.toString()))) == 0,
            "Wayland registry/bind contract changed: " + work
        );
        System.out.println("PASS: actual Wayland rendering/focused clipboard globals, privileged globals hidden and direct bind closed");
    }
    
    static void probe(List<String> arguments) throws IOException {
        require(arguments.size() == 2 && FORBIDDEN.contains(arguments.getFirst()), "WAYLAND interface global-id");
        try (var socket = connect()) {
            Map<String, Integer> visible = registry(socket);
            require(Collections.disjoint(visible.keySet(), FORBIDDEN), "Privileged Wayland global exposed");
            require(
                visible.keySet().containsAll(List.of("wl_compositor", "wl_shm", "wl_seat", "xdg_wm_base", "wl_data_device_manager")),
                "Required rendering/focused clipboard global hidden"
            );
            byte[] text = (arguments.getFirst() + "\0").getBytes(StandardCharsets.UTF_8);
            int padded = (text.length + 3) / 4 * 4;
            ByteBuffer body = bytes(16 + padded).putInt(Integer.parseInt(arguments.get(1))).putInt(text.length).put(text);
            body.position(8 + padded);
            body.putInt(1).putInt(4);
            socket.sendAll(message(2, 0, body.array()), 5000);
            socket.sendAll(message(1, 0, bytes(4).putInt(5).array()), 5000);
            boolean closed;
            try { closed = socket.receive(65536, 5000).length == 0; }
            catch (Posix.Failure reset) {
                require(reset.errno == 104, "Unexpected Wayland receive failure");
                closed = true;
            }
            require(closed, "Proxy forwarded a hidden interface bind");
        }
    }
    
}