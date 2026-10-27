package ru.pukpukov.pwrap;

import module java.base;
import static ru.pukpukov.pwrap.core.Json.*;
import com.google.gson.*;
import org.graalvm.nativeimage.ProcessProperties;
import ru.pukpukov.pwrap.backends.nixos.FileVerifier;
import ru.pukpukov.pwrap.backends.nixos.NativeBusChecks;
import ru.pukpukov.pwrap.backends.nixos.SystemdInstances;
import ru.pukpukov.pwrap.backends.nix.NixStorage;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.host.*;

/** Native-only regressions: real descriptors/sockets/flock, no installed service changes. */
public final class NativeChecks {
    
    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("_host")) {
            HostChecks.run(Path.of(args[1]), Path.of(args[2]), List.of(args).subList(3, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("_payload")) {
            HostPayload.run(List.of(args).subList(1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("_fixture")) {
            HostFixtures.run(List.of(args).subList(1, args.length));
            return;
        }
        if (args.length == 2 && args[0].equals("_maintenance")) {
            HostMaintenance.check(Path.of(args[1]));
            return;
        }
        if (args.length == 3 && args[0].equals("_egress")) {
            HostEgress.check(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length == 2 && args[0].equals("_activation")) {
            HostActivation.check(Path.of(args[1]));
            return;
        }
        if (args.length == 4 && args[0].equals("_services")) {
            HostServices.check(Path.of(args[1]), Path.of(args[2]), args[3]);
            return;
        }
        if (args.length == 3 && args[0].equals("_nm-bus-fixture")) {
            HostNmBus.fixture(args[1], args[2]);
            return;
        }
        if (args.length == 1 && args[0].equals("_nm-bus-check")) {
            HostNmBus.check("unix:path=/run/dbus/system_bus_socket", "unix:path=/run/pwrap-nm-bus/bus");
            return;
        }
        if (args.length == 2 && args[0].equals("_filter-check")) {
            KernelChecks.filter(Path.of(args[1]));
            return;
        }
        if (args.length == 2 && args[0].equals("_hold-lease")) {
            holdLease(Path.of(args[1]));
            return;
        }
        require(args.length == 4, "usage: NativeChecks PWRAP SETPRIV PRINTF DBUS_DAEMON");
        Path root = Files.createTempDirectory("pwrap-native-checks-");
        try {
            descriptors(root, args);
            authority(root, args);
            tasks(root, args);
            sockets(root);
            cohorts(root);
            processLeases(root);
            NativeBusChecks.run(root, args[3]);
            HostServices.protocol(root);
            System.out.println("PASS: native codecs, pins, cap0 preflight, pipes, stream/seqpacket sockets, cohorts, D-Bus");
        } finally { Fs.deleteTree(root); }
    }
    
    private static void descriptors(Path root, String[] args) throws Exception {
        Policy policy = Policy.read(JsonParser.parseString("{\"command\":[\"/app\"]}").getAsJsonObject());
        require(policy.equals(decode(encode(policy), Policy.class)), "Native record codec mismatch");
        Path source = root.resolve("grant");
        Files.writeString(source, "pinned");
        var grant = new FileAuthority.Grant(source.toString(), source.toString(), false);
        try (var pins = new FileAuthority.Pins(List.of(grant), FileAuthority.validator("/root", List.of()))) {
            if (Posix.getuid() == 0) {
                var command = List.of(
                    args[1], "--bounding-set=-all", "--inh-caps=-all", "--ambient-caps=-all", "--clear-groups", "--no-new-privs",
                    args[0], "_file-authority", "_preflight-pins", encode(List.of(grant.json())).toString(),
                    encode(pins.fds().stream().map(fd -> fd.n).toList()).toString()
                );
                try (var child = Posix.spawn(command, Map.of("LANG", "C"), pins.fds(), 0, 1, 2)) {
                    require(child.waitFor() == 0, "Native pinned cap0 preflight failed");
                }
            }
            Files.createSymbolicLink(root.resolve("alias"), source);
            try (var _ = FileAuthority.openPath(root.resolve("alias").toString())) {
                throw new AssertionError("No-follow open accepted a symlink");
            } catch (IOException expected) { }
        }
        var pipe = Posix.pipe();
        try (var read = pipe[0]; var write = pipe[1]; var child = Posix.spawn(List.of(args[2], "OK"), Map.of("LANG", "C"), List.of(), 0, write.n, 2)) {
            write.close();
            require(Arrays.equals(read.readAll(), new byte[]{'O', 'K'}) && child.waitFor() == 0, "Pipe/process ownership failed");
        }
    }
    
    private static void authority(Path root, String[] args) throws Exception {
        if (Posix.getuid() != 0) throw new IllegalStateException("Authority regression suite requires root to drop all capabilities");
        test_capability_free_state_parses_real_proc_status(args);
        test_directory_creation_executes_same_cap0_helper_with_only_parent_pin(root, args);
        test_cap0_directory_creation_checks_same_parent_inode_and_no_path_components(root, args);
        test_socket_preflight_checks_write_dac_for_connect(root, args);
    }
    
    private static int cap0(String[] args, List<String> request, List<Posix.Fd> pins) throws Exception {
        var command = Commands.join(
            List.of(
                args[1], "--bounding-set=-all", "--inh-caps=-all", "--ambient-caps=-all", "--clear-groups",
                "--no-new-privs", args[0], "_file-authority"
            ),
            request
        );
        try (var error = Posix.open("/dev/null", Posix.O_RDWR()); var child = Posix.spawn(command, Map.of("LANG", "C"), pins, 0, 1, error.n)) {
            return child.waitFor();
        }
    }
    
    private static void test_capability_free_state_parses_real_proc_status(String[] args) throws Exception {
        require(cap0(args, List.of("_preflight", "[]"), List.of()) == 0, "Real cap0 /proc status rejected");
    }
    
    private static void test_directory_creation_executes_same_cap0_helper_with_only_parent_pin(Path root, String[] args) throws Exception {
        var raw = RuntimeFixture.descriptor(root.resolve("absent"), new JsonObject());
        raw.addProperty("entry", args[0]);
        raw.addProperty("setpriv", args[1]);
        Config config = new Config(raw);
        Path file = Files.writeString(root.resolve("writable-file"), "existing");
        Path missing = root.resolve("optional-file"), persisted = root.resolve("new-directory/deep");
        var files = new Policy.FileAccess(
            List.of(), List.of(file.toString(), missing.toString()), List.of(persisted.toString()), List.of(), List.of(), null
        );
        var grants = FileAuthority.prepare(files, "/session-home", FileVerifier.mkdir(config.platform));
        require(grants.equals(List.of(
            new FileAuthority.Grant(file.toString(), file.toString(), true),
            new FileAuthority.Grant(persisted.toString(), persisted.toString(), true)
        )), "Optional writable/persist preparation changed");
        require(!Files.exists(missing) && Files.isRegularFile(file) && Files.isDirectory(persisted), "Only persist may create directories");
    }
    
    private static void test_cap0_directory_creation_checks_same_parent_inode_and_no_path_components(Path root, String[] args) throws Exception {
        Path parent = Files.createDirectory(root.resolve("mkdir-parent"));
        try (var pin = FileAuthority.openPath(parent.toString())) {
            for (String name : List.of("", ".", "..", "/escape", "nested/child", "null\0")) {
                var request = new JsonObject();
                request.addProperty("source", parent.toString());
                request.addProperty("fd", pin.n);
                request.addProperty("name", name);
                require(cap0(args, List.of("_mkdir", request.toString()), List.of(pin)) != 0, "Invalid anchored mkdir component accepted");
            }
            Files.move(parent, root.resolve("mkdir-original"));
            Files.createDirectory(parent);
            var request = new JsonObject();
            request.addProperty("source", parent.toString());
            request.addProperty("fd", pin.n);
            request.addProperty("name", "child");
            require(cap0(args, List.of("_mkdir", request.toString()), List.of(pin)) != 0, "Changed parent inode accepted by cap0 mkdir");
            require(!Files.exists(parent.resolve("child")) && !Files.exists(root.resolve("mkdir-original/child")), "Rejected mkdir had side effects");
        }
    }
    
    private static void test_socket_preflight_checks_write_dac_for_connect(Path root, String[] args) throws Exception {
        Path endpoint = root.resolve("dac-socket");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(endpoint));
            String grants = encode(List.of(new FileAuthority.Grant(endpoint.toString(), endpoint.toString(), false))).toString();
            Fs.mode(endpoint, 0400);
            require(cap0(args, List.of("_preflight", grants), List.of()) != 0, "Socket read permission authorized connect without write DAC");
            Fs.mode(endpoint, 0200);
            require(cap0(args, List.of("_preflight", grants), List.of()) == 0, "Socket write DAC incorrectly requires file read permission");
        }
    }
    
    private static void tasks(Path root, String[] args) throws Exception {
        Path work = Files.createDirectory(root.resolve("tasks")), argv = work.resolve("argv"), preflight = work.resolve("preflight");
        Path inherited = work.resolve("inherited");
        Path selected = Files.createDirectory(work.resolve("profile"));
        Path privateDir = Files.createDirectory(work.resolve("private")), retired = work.resolve("retired");
        Path bwrap = RuntimeFixture.script(work.resolve("bwrap"),
            "printf '%s\\n' \"$@\" > " + RuntimeFixture.quote(argv) + "\nprevious=\"\"\nfor arg do\n"
                + " if [ \"$previous\" = --bind-fd ]; then " + RuntimeFixture.tool("readlink")
                + " /proc/$$/fd/$arg > " + RuntimeFixture.quote(inherited)
                + "; fi\n previous=$arg\ndone\nexit 7\n"
        );
        Path manager = RuntimeFixture.script(
            work.resolve("systemd-run"), "printf called > " + RuntimeFixture.quote(work.resolve("manager")) + "\nexit 47\n"
        );
        Path privilege = RuntimeFixture.script(work.resolve("setpriv"),
            "printf '%s\\n' \"$@\" >> " + RuntimeFixture.quote(preflight) + "\n"
                + RuntimeFixture.quote(Path.of(args[1])) + " \"$@\" || exit 1\nif [ -e "
                + RuntimeFixture.quote(work.resolve("rename")) + " ]; then " + RuntimeFixture.tool("mv") + " "
                + RuntimeFixture.quote(selected) + " " + RuntimeFixture.quote(retired) + "; "
                + RuntimeFixture.tool("ln") + " -s " + RuntimeFixture.quote(privateDir) + " "
                + RuntimeFixture.quote(selected) + "; fi\n"
        );
        String executable = Commands.executable("true");
        Path closure = Files.writeString(work.resolve("closure"), NixStorage.root(executable) + "\n");
        var descriptor = RuntimeFixture.descriptor(work.resolve("absent"), new JsonObject());
        descriptor.addProperty("entry", args[0]);
        descriptor.addProperty("setpriv", privilege.toString());
        descriptor.addProperty("bwrap", bwrap.toString());
        descriptor.addProperty("systemdRun", manager.toString());
        descriptor.addProperty("filter", "/dev/null");
        descriptor.addProperty("baseClosure", closure.toString());
        Path runtime = Files.writeString(work.resolve("runtime.json"), descriptor.toString());
        Path manifest = work.resolve("task.json");
        var task = new JsonObject();
        task.addProperty("execution", "direct");
        task.addProperty("home", work.resolve("home").toString());
        task.add("command", encode(List.of(executable, "argument", "")));
        task.add("writable", encode(List.of(selected.toString())));
        task.add("inheritEnvironment", encode(List.of("VERBOSE")));
        Files.writeString(manifest, task.toString());
        test_direct_uses_common_payload_without_systemd_or_network_discovery(args, runtime, manifest, argv, work);
        test_mutable_sources_go_through_shared_pins_and_cap0_preflight(args, runtime, manifest, argv, preflight);
        Files.writeString(work.resolve("rename"), "");
        test_parent_renamed_after_preflight_does_not_change_pinned_inode(args, runtime, manifest, inherited, retired);
        Files.delete(work.resolve("rename"));
        test_managed_failure_never_falls_back_to_direct_execution(args, runtime, manifest, argv, task, work);
    }
    
    private static int task(String[] args, Path runtime, Path manifest) throws Exception {
        var command = List.of(args[0], runtime.toString(), "profile", manifest.toString());
        return Commands.result(command, Map.of("VERBOSE", "yes", "HOST_SECRET", "must-not-leak", "HOME", "/wrong-service-home"), true).code();
    }
    
    private static void test_direct_uses_common_payload_without_systemd_or_network_discovery(
        String[] args, Path runtime, Path manifest, Path argv, Path work
    ) throws Exception {
        require(task(args, runtime, manifest) == 7, "Direct task did not propagate common payload exit code");
        require(!Files.exists(work.resolve("manager")), "Direct task contacted systemd");
        require(Files.readAllLines(argv).contains("--seccomp"), "Direct task bypassed sandbox");
    }
    
    private static void test_mutable_sources_go_through_shared_pins_and_cap0_preflight(
        String[] args, Path runtime, Path manifest, Path argv, Path preflight
    ) throws Exception {
        Files.delete(preflight);
        require(task(args, runtime, manifest) == 7, "Pinned direct task failed");
        var command = Files.readAllLines(argv);
        for (String flag : List.of("--bind-fd", "--seccomp", "--unshare-all", "--clearenv", "VERBOSE", "yes"))
            require(command.contains(flag), "Missing common direct-task contract: " + flag);
        require(command.subList(command.size() - 2, command.size()).equals(List.of("argument", "")), "Direct task argv changed");
        require(command.stream().noneMatch(s -> s.contains("HOST_SECRET")), "Host secret leaked to direct task");
        require(
            Files.readAllLines(preflight).stream().filter(s -> s.equals("_preflight-pins")).count() == 1,
            "Pinned task preflight not called exactly once"
        );
    }
    
    private static void test_parent_renamed_after_preflight_does_not_change_pinned_inode(
        String[] args, Path runtime, Path manifest, Path inherited, Path retired
    ) throws Exception {
        require(task(args, runtime, manifest) == 7, "Pinned authority was replaced after preflight");
        require(Files.readString(inherited).strip().equals(retired.toString()), "Payload received replacement name instead of original pinned inode");
    }
    
    private static void test_managed_failure_never_falls_back_to_direct_execution(
        String[] args, Path runtime, Path manifest, Path argv, JsonObject task, Path work
    ) throws Exception {
        var managed = task.deepCopy();
        for (String key : List.of("execution", "home", "inheritEnvironment", "writable")) managed.remove(key);
        Files.writeString(manifest, managed.toString());
        Files.delete(argv);
        require(task(args, runtime, manifest) == 47, "Manager failure not propagated");
        require(Files.exists(work.resolve("manager")) && !Files.exists(argv), "Manager failure fell back to a direct payload");
    }
    
    private static final Instances.Codec<JsonObject, JsonObject> PROCESS_CODEC = new Instances.Codec<>("process-v1", JsonObject.class, JsonObject.class);
    
    private static Instances processInstances(Path root) {
        return SystemdInstances.create(root.resolve("instances"), root.resolve("groups"), root.resolve("proc"));
    }
    
    private static JsonObject intent() {
        var value = new JsonObject();
        value.addProperty("intent", 1);
        return value;
    }
    
    private static void holdLease(Path root) throws Exception {
        try (var lease = processInstances(root).lease("process", PROCESS_CODEC, intent(), NativeChecks::intent, true)) {
            Files.writeString(root.resolve("ready"), encode(lease.snapshot).toString());
            while (!Files.exists(root.resolve("release"))) Thread.sleep(10);
        }
    }
    
    private static Process holder(Path root) throws Exception {
        Files.createDirectories(root);
        Process child = Commands.process(List.of(ProcessProperties.getExecutableName(), "_hold-lease", root.toString())).inheritIO().start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!Files.exists(root.resolve("ready"))) {
            child.destroyForcibly().waitFor();
            throw new IllegalStateException("Lease child never became ready");
        }
        return child;
    }
    
    private static void processLeases(Path root) throws Exception {
        test_real_process_pending_lease_prevents_double_capture(root.resolve("real-pending"));
        test_stoppost_reclaims_scratch_after_a_real_pending_launcher_is_killed(root.resolve("real-killed"));
    }
    
    private static void test_real_process_pending_lease_prevents_double_capture(Path root) throws Exception {
        Process child = holder(root);
        try {
            try (
                var lease = processInstances(root).lease(
                    "process", PROCESS_CODEC, intent(),
                    () -> { throw new AssertionError("A live real process recaptured discovery"); }, true
                )
            ) {
                require(lease.snapshot.state().equals(intent()), "Real pending snapshot was not borrowed");
            }
            Files.writeString(root.resolve("release"), "");
            require(child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0, "Lease holder failed to exit cleanly");
            boolean[] captured = {false};
            try (var lease = processInstances(root).lease("process", PROCESS_CODEC, intent(), () -> { captured[0] = true; return intent(); }, true)) {
                require(captured[0], "Ended real process kept discovery frozen");
                require(lease.snapshot.state().equals(intent()), "Fresh capture changed");
            }
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
    }
    
    private static void test_stoppost_reclaims_scratch_after_a_real_pending_launcher_is_killed(Path root) throws Exception {
        Process child = holder(root);
        Path scratch = Path.of(decode(read(root.resolve("ready")).get("scratch"), String.class));
        try {
            try (var lease = processInstances(root).lease(
                "process", PROCESS_CODEC, intent(), () -> { throw new AssertionError("Pending child was ignored"); }, true
            )) {
                require(Files.isDirectory(scratch) && scratch.toString().equals(lease.snapshot.scratch()), "Pending process scratch was not shared");
            }
        } finally { child.destroyForcibly().waitFor(); }
        processInstances(root).release("process", "pwrap-killed.service");
        require(!Files.exists(scratch), "StopPost did not reclaim killed launcher scratch");
        try (var files = Files.list(root.resolve("instances/process"))) {
            require(files.noneMatch(p -> p.toString().endsWith(".lease")), "Killed launcher lease survived cleanup");
        }
    }
    
    private static void sockets(Path root) throws Exception {
        var pair = Posix.socketPair();
        byte[] bytes = new byte[1_048_576];
        new Random(1).nextBytes(bytes);
        try (var sender = pair[0]; var receiver = pair[1]) {
            var received = new CompletableFuture<byte[]>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    var out = new ByteArrayOutputStream();
                    while (out.size() < bytes.length) out.write(receiver.receive(4096, 5000));
                    received.complete(out.toByteArray());
                }
                catch (Exception error) { received.completeExceptionally(error); }
            });
            sender.sendAll(bytes, 5000);
            require(Arrays.equals(bytes, received.get(10, TimeUnit.SECONDS)), "Partial stream write lost data");
            reader.join();
        }
    }
    
    private static void cohorts(Path root) throws Exception {
        var instances = SystemdInstances.create(root.resolve("instances"), root.resolve("cgroups"), root.resolve("proc"));
        var codec = new Instances.Codec<>("test", Policy.NetworkSettings.class, Policy.FileAccess.class);
        var request = new Policy.NetworkSettings(List.of(new Endpoint.Port(80)), List.of());
        var state = new Policy.FileAccess(List.of("/selected"), List.of(), List.of(), List.of(), List.of(), null);
        Path scratch;
        try (var first = instances.lease("cohort", codec, request, () -> state, true)) {
            scratch = Path.of(required(first.snapshot.scratch(), "Expected fixture value"));
            require(Files.isDirectory(scratch), "Scratch not created");
            try (var second = instances.lease("cohort", codec, request, () -> { throw new AssertionError("Live capture repeated"); }, true)) {
                require(first.snapshot.equals(second.snapshot), "Live cohort not frozen");
            }
            require(Files.exists(scratch), "Scratch removed while a lease is live");
            instances.register("cohort", null, "fingerprint", Map.of(), Map.of(), false);
            String unit = "pwrap-test.service";
            Path group = root.resolve("cgroups").resolve(unit);
            Files.createDirectories(group);
            Files.writeString(group.resolve("cgroup.events"), "populated 1\n");
            instances.register("cohort", unit, "fingerprint", Map.of(), Map.of("test", encode(first.snapshot).getAsJsonObject()), true);
        }
        require(Files.exists(scratch), "Published live unit lost scratch after outer lease exit");
        instances.release("cohort", "pwrap-test.service");
        require(!Files.exists(scratch), "Last unit exit did not clean scratch");
    }
    
}