package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import ru.pukpukov.pwrap.backends.nix.PackageExport;
import ru.pukpukov.pwrap.backends.nixos.*;
import ru.pukpukov.pwrap.core.*;

/** CLI and native helper dispatcher. */
public final class Main {

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("_file-authority")) {
                System.exit(FileAuthority.main(List.of(args).subList(1, args.length)));
                return;
            }
            if (args.length > 0 && args[0].equals("_package-export")) {
                Json.require(args.length == 5, "Invalid package export arguments");
                var aliases = Json.decode(JsonParser.parseString(args[4]), new TypeToken<List<String>>() {});
                PackageExport.export(Path.of(args[1]), Path.of(args[2]), args[3], aliases);
                return;
            }
            if (args.length > 0 && args[0].equals("_service-egress")) {
                Json.require(args.length == 3, "Invalid native egress arguments");
                ServiceEgress.main(Path.of(args[1]), args[2]);
                return;
            }
            if (args.length > 0 && args[0].equals("_nm-audit")) {
                Json.require(args.length >= 2, "Missing libseccomp path");
                NetworkManagerAudit.run(args[1], List.of(args).subList(2, args.length));
                return;
            }
            if (args.length > 0 && args[0].equals("_nm-bus")) {
                int code = NetworkManagerBus.run(List.of(args).subList(1, args.length));
                System.exit(code < 0 ? 128 - code : code);
                return;
            }
            if (args.length == 1 && args[0].equals("_doctor")) {
                doctor();
                return;
            }
            if (args.length >= 2 && args[0].equals("_policy-check")) {
                for (String name : List.of(args).subList(1, args.length)) {
                    JsonObject policy = Json.read(Path.of(name));
                    policy.addProperty("manifest", name);
                    Policy.read(policy);
                }
                return;
            }
            if (args.length == 0) throw new Cli.Usage("Missing runtime descriptor");
            Path runtime = Path.of(args[0]);
            Config config = new Config(Json.read(runtime));
            List<String> arguments = List.of(args).subList(1, args.length);
            if (arguments.size() == 3 && arguments.getFirst().equals("_release")) {
                SystemdInstances.HOST.release(arguments.get(1), arguments.get(2));
                return;
            }
            if (arguments.size() == 2 && arguments.getFirst().equals("_launch")) {
                Platform.Unit unit = Json.decode(Json.read(Path.of(arguments.get(1))), Platform.Unit.class);
                Platform.configure(unit, config.platform);
                unit.register(config.platform, true);
                int code;
                try {
                    code = Payload.run(unit.prepared(), config, unit.boundary());
                } finally {
                    SystemdInstances.HOST.release(unit.prepared().policy().name(), unit.unit());
                }
                System.exit(code < 0 ? 128 - code : code);
                return;
            }
            int endOptions = arguments.indexOf("--");
            List<String> switches = arguments.subList(0, endOptions < 0 ? arguments.size() : endOptions);
            if (switches.contains("--version")) {
                System.out.println("pwrap 0.1.0-beta.1");
                return;
            }
            if (switches.contains("--help") || switches.contains("-h")) {
                Cli.help();
                return;
            }
            Cli.Options options = Cli.parse(arguments);
            if (options.action() == Cli.Action.LIST || options.action() == Cli.Action.INSPECT) {
                var profiles = Cli.profiles(config);
                if (options.action() == Cli.Action.INSPECT && options.profile() != null) {
                    String path = profiles.get(options.profile());
                    Json.require(path != null, "Unknown profile: " + options.profile());
                    System.out.print(Files.readString(Path.of(path)));
                } else System.out.println(String.join("\n", new TreeSet<>(profiles.keySet())));
                return;
            }
            Policy policy = Cli.policy(options, config);
            Json.require(Posix.geteuid() == 0, "The nixos platform requires root; no sudo/setuid fallback");
            int code;
            try (var lifetime = new Lifetime()) {
                Launch.Prepared prepared = Launch.prepare(policy, config, lifetime);
                if (policy.invocation().execution() == Policy.Execution.DIRECT) code = Payload.run(prepared, config, null);
                else {
                    Activation.checkDomain(prepared, config);
                    code = Platform.run(prepared, config.platform, runtime, (p, boundary) -> Activation.activate(p, boundary, config));
                }
            }
            System.exit(code < 0 ? 128 - code : code);
        } catch (Cli.Usage error) {
            System.err.println("pwrap: " + error.getMessage());
            Cli.help();
            System.exit(2);
        } catch (Exception error) {
            System.err.println("pwrap: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void doctor() throws IOException {
        String status = Files.readString(Path.of("/proc/self/status"));
        for (String field : List.of("CapEff", "CapBnd")) {
            Json.require(Pattern.compile("(?m)^" + field + ":\\s*0000000000000000$").matcher(status).find(), "Expected cap0 payload");
        }
        Json.require(
            Pattern.compile("(?m)^NoNewPrivs:\\s*1$").matcher(status).find()
                && Pattern.compile("(?m)^Seccomp:\\s*2$").matcher(status).find(),
            "Expected NNP/seccomp payload"
        );
        System.out.println("PASS: minimal sandbox, cap0, NNP, seccomp, enforced no-network cgroup");
    }
    
}