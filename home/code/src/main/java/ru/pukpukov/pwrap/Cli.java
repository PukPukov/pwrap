package ru.pukpukov.pwrap;

import module java.base;
import com.google.gson.reflect.TypeToken;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.tainting.qual.Untainted;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;

/** CLI/manifest JSON is decoded once, after applying explicit command-line overrides. */
final class Cli {

    static final class Usage extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Usage(String message) { super(message); }
    }

    enum Action { RUN, SHELL, START, PROFILE, LIST, INSPECT, DOCTOR }

    private static final Map<String, String> LISTS = Map.ofEntries(
        Map.entry("--read", "readOnly"), Map.entry("--write", "writable"), Map.entry("--persist", "persist"),
        Map.entry("--immutable-data", "immutableData"), Map.entry("--runtime", "runtime"),
        Map.entry("--dbus-talk", "dbusTalk"), Map.entry("--dbus-own", "dbusOwn"),
        Map.entry("--dbus-call", "dbusCalls"), Map.entry("--dbus-broadcast", "dbusBroadcasts")
    );

    private static final Map<String, String> FLAGS = Map.of(
        "--desktop", "desktop", "--audio", "audio", "--allow-localhost", "allowLocalhost", "--allow-lan", "allowLAN"
    );

    record Options(Action action, @Nullable String profile, List<String> command, JsonObject changes, boolean changed) {}

    static void help() {
        System.out.println(
            "usage: pwrap {run,shell,start,profile,list,inspect,doctor} [profile] [options] [-- command ...]\n"
                + "Explicit root-session compartments; no implicit HOME/CWD sharing.\n"
                + "--net none|internet|lan|local|host  --read PATH  --write PATH  --persist PATH\n"
                + "--immutable-data PATH  --runtime PROGRAM  --cwd PATH  --env NAME=VALUE\n"
                + "--desktop  --gpu/--no-gpu  --audio  --provider PERMISSION=PROVIDER\n"
                + "--open-web  --open-files  --open-uri SCHEME  --[no-]file-ask  --[no-]uri-ask  --dbus-talk NAME  --dbus-own NAME\n"
                + "--dbus-call JSON  --dbus-broadcast JSON  --allow-localhost  --allow-lan\n"
                + "--host-abstract-sockets  --shared-tmp/--no-shared-tmp  --tasks-max N  --memory SIZE"
        );
    }

    private static void append(JsonObject object, String key, JsonElement value) {
        JsonArray values = object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
        values.add(value);
        object.add(key, values);
    }

    private static JsonObject provider(JsonObject changes, String operation) {
        JsonObject providers = changes.has("providers") ? changes.getAsJsonObject("providers") : new JsonObject();
        JsonObject options = providers.has(operation) ? providers.getAsJsonObject(operation) : new JsonObject();
        providers.add(operation, options);
        changes.add("providers", providers);
        return options;
    }

    private static void mergeProviders(JsonObject target, JsonObject changes) {
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                JsonObject nested = target.has(key) ? target.getAsJsonObject(key) : new JsonObject();
                mergeProviders(nested, value.getAsJsonObject());
                target.add(key, nested);
            } else if (key.equals("schemes") && target.has(key)) {
                JsonArray schemes = target.getAsJsonArray(key);
                value.getAsJsonArray().forEach(schemes::add);
            } else target.add(key, value);
        }
    }

    static Options parse(List<String> arguments) throws IOException {
        int separator = arguments.indexOf("--");
        if (separator < 0) separator = arguments.size();
        List<String> command = new ArrayList<>(arguments.subList(Math.min(separator + 1, arguments.size()), arguments.size()));
        JsonObject changes = new JsonObject();
        List<String> positional = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < separator; i++) {
            String arg = arguments.get(i);
            if (!arg.startsWith("-")) {
                positional.add(arg);
                continue;
            }
            String option = arg, inline = null;
            int equal = arg.indexOf('=');
            if (equal > 0) {
                option = arg.substring(0, equal);
                inline = arg.substring(equal + 1);
            }
            if (Set.of("--open-web", "--open-files", "--file-ask", "--no-file-ask", "--uri-ask", "--no-uri-ask").contains(option)) {
                if (inline != null) throw new Usage("Unexpected argument: " + arg);
                boolean file = option.equals("--open-files") || option.contains("file-ask");
                JsonObject selected = provider(changes, file ? "openFile" : "uri");
                if (option.contains("ask")) selected.addProperty("ask", !option.startsWith("--no-"));
                else {
                    selected.addProperty("enable", true);
                    if (!file) for (String scheme : List.of("http", "https")) append(selected, "schemes", new JsonPrimitive(scheme));
                }
                changed = true;
                continue;
            }
            if (FLAGS.containsKey(option)) {
                if (inline != null) throw new Usage("Unexpected argument: " + arg);
                changes.addProperty(FLAGS.get(option), true);
                changed = true;
                continue;
            }
            if (Set.of("--gpu", "--no-gpu", "--shared-tmp", "--no-shared-tmp").contains(option)) {
                if (inline != null) throw new Usage("Unexpected argument: " + arg);
                changes.addProperty(option.contains("gpu") ? "gpu" : "sharedTmp", !option.startsWith("--no-"));
                changed = true;
                continue;
            }
            if (option.equals("--host-abstract-sockets")) {
                if (inline != null) throw new Usage("Unexpected argument: " + arg);
                JsonObject ipc = new JsonObject();
                ipc.addProperty("hostAbstractSockets", true);
                changes.add("ipc", ipc);
                changed = true;
                continue;
            }
            if (!LISTS.containsKey(option) && !Set.of("--env", "--provider", "--net", "--memory", "--tasks-max", "--cwd", "--open-uri").contains(option)) {
                throw new Usage("Unknown option: " + option);
            }
            String value = inline;
            if (value == null) {
                if (++i >= separator) throw new Usage(option + " needs a value");
                value = arguments.get(i);
            }
            changed = true;
            if (LISTS.containsKey(option)) {
                JsonElement item = option.equals("--dbus-call") || option.equals("--dbus-broadcast") ?
                    JsonParser.parseString(value) : new JsonPrimitive(option.equals("--runtime") ? Commands.executable(value) : value);
                append(changes, LISTS.get(option), item);
                continue;
            }
            switch (option) {
                case "--env", "--provider" -> {
                    int at = value.indexOf('=');
                    if (at < 1) throw new Usage(option + " needs NAME=VALUE");
                    String key = value.substring(0, at), selected = value.substring(at + 1);
                    if (option.equals("--env") && !key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new Usage("--env needs NAME=VALUE");
                    if (option.equals("--provider") && !(key.equals("audio") && Set.of("direct", "nixos").contains(selected))) {
                        throw new Usage("--provider needs audio=direct or audio=nixos");
                    }
                    String field = option.equals("--env") ? "environment" : "providers";
                    JsonObject map = changes.has(field) ? changes.getAsJsonObject(field) : new JsonObject();
                    map.addProperty(key, selected);
                    changes.add(field, map);
                }
                case "--open-uri" -> {
                    JsonObject selected = provider(changes, "uri");
                    selected.addProperty("enable", true);
                    append(selected, "schemes", new JsonPrimitive(value));
                }
                case "--net" -> changes.addProperty("network", value);
                case "--memory" -> changes.addProperty("memoryMax", value);
                case "--tasks-max" -> {
                    try { changes.addProperty("tasksMax", Long.parseLong(value)); }
                    catch (NumberFormatException e) { throw new Usage("--tasks-max requires an integer"); }
                }
                case "--cwd" -> changes.addProperty("cwd", Fs.resolve(value, Session.from(System.getenv()).home()));
                default -> throw new Usage("Unknown option: " + option);
            }
        }
        if (positional.isEmpty() || positional.size() > 2) throw new Usage("Specify an action and at most one profile");
        Action action;
        try {
            action = Action.valueOf(positional.getFirst().equals(positional.getFirst().toLowerCase(Locale.ROOT)) ?
            positional.getFirst().toUpperCase(Locale.ROOT) : "INVALID");
        } catch (IllegalArgumentException e) { throw new Usage("Unknown action: " + positional.getFirst()); }
        return new Options(action, positional.size() == 2 ? positional.get(1) : null, List.copyOf(command), changes, changed);
    }

    static Map<String, String> profiles(Config config) throws IOException {
        JsonElement value = config.platform.metadata("profiles");
        return value.isJsonNull() ? Map.of() : decode(value, new TypeToken<Map<String, String>>() {});
    }

    static @Untainted Policy policy(Options options, Config config) throws IOException {
        boolean loaded = options.action == Action.START || options.action == Action.PROFILE;
        JsonObject document;
        if (loaded) {
            if (options.profile == null) throw new Usage(options.action.name().toLowerCase(Locale.ROOT) + " needs a profile name or manifest path");
            String manifest = options.action == Action.START ? profiles(config).get(options.profile) : options.profile;
            if (manifest == null) throw new Usage("Unknown profile: " + options.profile);
            document = read(Path.of(manifest));
            document.addProperty("manifest", Fs.real(manifest));
        } else {
            document = new JsonObject();
            document.addProperty("name", "run");
            document.add("command", new JsonArray());
        }
        for (var change : options.changes.entrySet()) {
            String key = change.getKey();
            JsonElement value = change.getValue();
            if (LISTS.containsValue(key)) for (JsonElement element : value.getAsJsonArray()) append(document, key, element);
            else if (key.equals("providers")) {
                JsonObject merged = document.has(key) ? document.getAsJsonObject(key) : new JsonObject();
                mergeProviders(merged, value.getAsJsonObject());
                document.add(key, merged);
            } else if (Set.of("environment", "ipc").contains(key)) {
                JsonObject merged = document.has(key) ? document.getAsJsonObject(key) : new JsonObject();
                value.getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                document.add(key, merged);
            } else document.add(key, value);
        }
        List<String> command = options.command;
        if (!loaded && command.isEmpty()) {
            if (options.action == Action.SHELL) command = List.of(config.platform.path(SHELL), "--noprofile", "--norc");
            else if (options.action == Action.DOCTOR) command = List.of(config.platform.entry, "_doctor");
            else throw new Usage("Specify a command after --");
        }
        if (
            loaded && !options.changed &&
            !(document.has("execution") && decode(document.get("execution"), Policy.Execution.class) == Policy.Execution.DIRECT)
        ) {
            document.add("activationArguments", encode(command));
        }
        JsonArray argv = document.getAsJsonArray("command");
        for (String argument : command) argv.add(argument);
        return Policy.read(document);
    }
    
}