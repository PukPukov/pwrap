package ru.pukpukov.pwrap.host;

import module java.base;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;

/** Optional deployed-service checks: fixed public markers, no credentials or useful PHP endpoint. */
public final class HostServices {
    
    private record Report(
        Map<String, Boolean> hidden, boolean codeReadonly, boolean ownStateWritable, boolean noOutboundIP,
        boolean cap0, boolean bounding0, boolean seccomp
    ) {
        boolean passed() {
            return !hidden.isEmpty() && hidden.values().stream().allMatch(Boolean::booleanValue) &&
                codeReadonly && ownStateWritable && noOutboundIP && cap0 && bounding0 && seccomp;
        }
    }
    
    static byte[] record(int kind, byte[] body) {
        require(body.length <= 65535 && kind > 0 && kind <= 255, "Invalid FastCGI fixture record");
        return ByteBuffer.allocate(8 + body.length).put((byte) 1).put((byte) kind).putShort((short) 1)
            .putShort((short) body.length).put((byte) 0).put((byte) 0).put(body).array();
    }
    
    private static byte[] exact(Posix.Unix socket, int count) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < count) {
            byte[] part = socket.receive(count - bytes.size(), 15000);
            if (part.length == 0) throw new EOFException("FastCGI fixture response ended early");
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }
    
    static void size(ByteArrayOutputStream bytes, int size) {
        require(size >= 0 && size < 1 << 30, "Invalid FastCGI size");
        if (size < 128) bytes.write(size);
        else bytes.writeBytes(ByteBuffer.allocate(4).putInt(size | 0x80000000).array());
    }
    
    static byte[] params(Map<String, String> environment) {
        var bytes = new ByteArrayOutputStream();
        for (var entry : environment.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8), value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            size(bytes, key.length);
            size(bytes, value.length);
            bytes.writeBytes(key);
            bytes.writeBytes(value);
        }
        return bytes.toByteArray();
    }
    
    private static Report fastcgi(Path socketPath, Path script) throws IOException {
        Map<String, String> environment = Map.ofEntries(
            Map.entry("SCRIPT_FILENAME", script.toString()), Map.entry("SCRIPT_NAME", "/pwrap-proof.php"),
            Map.entry("REQUEST_METHOD", "GET"), Map.entry("REQUEST_URI", "/pwrap-proof.php"),
            Map.entry("SERVER_NAME", "localhost"), Map.entry("SERVER_PORT", "80"),
            Map.entry("SERVER_PROTOCOL", "HTTP/1.1"), Map.entry("GATEWAY_INTERFACE", "CGI/1.1"),
            Map.entry("REDIRECT_STATUS", "200")
        );
        try (var socket = Posix.Unix.connect(socketPath.toString(), Posix.SOCK_STREAM(), 15000)) {
            var request = new ByteArrayOutputStream();
            request.writeBytes(record(1, new byte[]{0, 1, 0, 0, 0, 0, 0, 0}));
            request.writeBytes(record(4, params(environment)));
            request.writeBytes(record(4, new byte[0]));
            request.writeBytes(record(5, new byte[0]));
            socket.sendAll(request.toByteArray(), 15000);
            var output = new ByteArrayOutputStream();
            boolean ended = false;
            for (int count = 0; count < 4096 && !ended; count++) {
                ByteBuffer header = ByteBuffer.wrap(exact(socket, 8));
                int version = header.get() & 255, kind = header.get() & 255, id = header.getShort() & 65535;
                int length = header.getShort() & 65535, padding = header.get() & 255;
                require(version == 1 && id == 1, "Invalid FastCGI response identity");
                byte[] data = exact(socket, length);
                exact(socket, padding);
                if (kind == 6) output.writeBytes(data);
                require(output.size() <= 2_097_152, "Oversized FastCGI fixture reply");
                if (kind == 3) {
                    require(data.length == 8 && ByteBuffer.wrap(data).getInt() == 0 && data[4] == 0, "FastCGI fixture request failed");
                    ended = true;
                }
            }
            require(ended, "FastCGI fixture response exceeded record budget");
            String response = output.toString(StandardCharsets.UTF_8);
            int boundary = response.indexOf("\r\n\r\n");
            require(boundary >= 0, "Missing FastCGI response headers");
            return Json.decode(JsonParser.parseString(response.substring(boundary + 4)), Report.class);
        }
    }
    
    private static String php(String string) { return "'" + string.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    
    /** Roundtrip against an owned Unix socket; never contacts the deployed web stack. */
    public static void protocol(Path root) throws Exception {
        Path socket = root.resolve("fcgi"), script = root.resolve("public-marker.php");
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            var server = threads.submit(() -> {
                try (var channel = listener.accept()) {
                    var input = Channels.newInputStream(channel);
                    var environment = new LinkedHashMap<String, String>();
                    for (int expected : List.of(1, 4, 4, 5)) {
                        byte[] bytes = input.readNBytes(8);
                        require(bytes.length == 8, "Short fixture request header");
                        ByteBuffer header = ByteBuffer.wrap(bytes);
                        require(header.get() == 1 && header.get() == expected && header.getShort() == 1, "Wrong fixture request identity");
                        int length = header.getShort() & 65535, padding = header.get() & 255;
                        byte[] body = input.readNBytes(length);
                        require(body.length == length && input.readNBytes(padding).length == padding, "Short fixture request body");
                        if (expected == 4 && length != 0) {
                            ByteBuffer pairs = ByteBuffer.wrap(body);
                            while (pairs.hasRemaining()) {
                                int keySize = fixtureSize(pairs), valueSize = fixtureSize(pairs);
                                byte[] key = new byte[keySize], value = new byte[valueSize];
                                pairs.get(key).get(value);
                                environment.put(new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
                            }
                        }
                    }
                    require(
                        script.toString().equals(environment.get("SCRIPT_FILENAME")) &&
                            "GET".equals(environment.get("REQUEST_METHOD")),
                        "FastCGI request parameters changed"
                    );
                    var response = new ByteArrayOutputStream();
                    response.writeBytes(record(7, "public diagnostic".getBytes(StandardCharsets.UTF_8)));
                    response.writeBytes(record(
                        6, ("Content-Type: application/json\r\n\r\n" +
                            Json.encode(new Report(Map.of("fixture", true), true, true, true, true, true, true))
                        ).getBytes(StandardCharsets.UTF_8)
                    ));
                    response.writeBytes(record(6, new byte[0]));
                    response.writeBytes(record(3, new byte[8]));
                    var output = Channels.newOutputStream(channel);
                    byte[] bytes = response.toByteArray();
                    for (int offset = 0; offset < bytes.length; offset += 3) output.write(bytes, offset, Math.min(3, bytes.length - offset));
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try {
                require(fastcgi(socket, script).passed(), "Owned FastCGI roundtrip failed");
                server.get(10, TimeUnit.SECONDS);
                System.out.println("PASS: native FastCGI framing, parameters, fragmented response and typed report on own socket");
            } finally {
                server.cancel(true);
                listener.close();
            }
        } finally { Files.deleteIfExists(socket); }
    }
    
    private static int fixtureSize(ByteBuffer input) {
        int first = input.get() & 255;
        return first < 128 ? first : ((first & 127) << 24) | ((input.get() & 255) << 16) |
            ((input.get() & 255) << 8) | (input.get() & 255);
    }
    
    public static void check(Path site, Path socket, String hostname) throws Exception {
        require(
            Posix.geteuid() == 0 && Files.isDirectory(site.resolve("images")) && Files.exists(socket) &&
                hostname.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"),
            "Supply real document root, FPM socket and hostname explicitly"
        );
        String token = UUID.randomUUID().toString().replace("-", "");
        Path work = Files.createTempDirectory(Fs.parent(socket), "pwrap-fpm-");
        Fs.mode(work, 0755);
        Path script = work.resolve("proof.php"), own = site.resolve("images/.pwrap-write-" + token), code = site.resolve(".pwrap-write-" + token);
        require(!Files.exists(own, LinkOption.NOFOLLOW_LINKS) && !Files.exists(code, LinkOption.NOFOLLOW_LINKS), "Fixture marker collision");
        var hidden = List.of(
            "/etc/shadow", "/run/systemd/private", "/run/docker.sock", "/run/caddy/admin.sock",
            Path.of(System.getenv().getOrDefault("HOME", "/root"), ".codex").toString(),
            Path.of(System.getenv().getOrDefault("HOME", "/root"), ".ssh").toString()
        );
        String source = "<?php\n$paths=json_decode(" + php(Json.encode(hidden).toString()) + ",true); $hidden=[]; "
            + "foreach($paths as $path){$hidden[$path]=!file_exists($path);}\n"
            + "$checks=['hidden'=>$hidden,'codeReadonly'=>@file_put_contents(" + php(code.toString()) + ",'harmless probe')===false,"
            + "'ownStateWritable'=>@file_put_contents(" + php(own.toString()) + ",'harmless probe')!==false];"
            + " @unlink(" + php(own.toString()) + ");\n"
            + "$ip=@stream_socket_client('tcp://1.1.1.1:443',$errno,$error,0.2); $checks['noOutboundIP']=$ip===false; "
            + "if($ip){fclose($ip);} $status=file_get_contents('/proc/self/status');\n"
            + "$checks['cap0']=str_contains($status,\"CapEff:\\t0000000000000000\");"
            + " $checks['bounding0']=str_contains($status,\"CapBnd:\\t0000000000000000\");"
            + " $checks['seccomp']=str_contains($status,\"Seccomp:\\t2\");"
            + " header('Content-Type: application/json'); echo json_encode($checks);\n";
        try {
            Files.writeString(script, source, StandardOpenOption.CREATE_NEW);
            Fs.mode(script, 0644);
            Report report = fastcgi(socket, script);
            require(report.passed(), "Deployed FPM boundary check failed: " + Json.encode(report));
            System.out.println("PASS real FPM worker: " + Json.encode(report));
        } finally {
            Files.deleteIfExists(own);
            Files.deleteIfExists(code);
            Files.deleteIfExists(script);
            Files.delete(work);
        }
        Path upload = site.resolve("images/pwrap-proof-" + token + ".php"), html = site.resolve("images/pwrap-proof-" + token + ".html");
        var created = new ArrayList<Path>();
        try {
            Files.writeString(upload, "<?php echo 'UNEXPECTED_EXECUTION';", StandardOpenOption.CREATE_NEW);
            created.add(upload);
            Files.writeString(html, "<!doctype html><title>Harmless static pwrap probe</title>", StandardOpenOption.CREATE_NEW);
            created.add(html);
            var curl = List.of(RuntimeFixture.tool("curl"), "--noproxy", "*", "--resolve", hostname + ":443:127.0.0.1", "--max-time", "15", "-sS");
            String status = Commands.output(Commands.join(curl, List.of(
                "-o", "/dev/null", "-w", "%{http_code}", "https://" + hostname + "/images/" + Fs.name(upload)
            )));
            require(status.equals("404"), "Uploaded PHP not refused: " + status);
            String headers = Commands.output(Commands.join(curl, List.of(
                "-D", "-", "-o", "/dev/null", "https://" + hostname + "/images/" + Fs.name(html)
            ))).toLowerCase(Locale.ROOT);
            require(
                headers.contains("content-security-policy: sandbox") && headers.contains("x-content-type-options: nosniff"),
                "Static upload response lacks sandbox/nosniff headers"
            );
            System.out.println("PASS Caddy uploads: PHP404, static CSP sandbox and nosniff");
        } finally { for (Path path : created) Files.delete(path); }
    }
}