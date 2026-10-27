package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import java.util.regex.Pattern;
import com.google.gson.*;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.core.Resources.Binding;
import static ru.pukpukov.pwrap.core.Json.*;
import static ru.pukpukov.pwrap.backends.nixos.PlatformConfig.Key.*;

/** Search paths are discovery inputs, never grants for a whole HOME/profile/output. */
public final class Desktop {
    
    public static final List<String> APPEARANCE_ENV = List.of(
        "XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "XCURSOR_SIZE", "XCURSOR_THEME",
        "GTK_THEME", "QT_QPA_PLATFORMTHEME", "QT_STYLE_OVERRIDE", "QT_QUICK_CONTROLS_STYLE"
    );
    public static final List<String> DISCOVERY_ENV = Commands.join(APPEARANCE_ENV, List.of(
        "HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CONFIG_DIRS", "XDG_DATA_DIRS",
        "GTK2_RC_FILES", "GTK_RC_FILES", "XCURSOR_PATH", "GTK_PATH", "QT_PLUGIN_PATH",
        "FONTCONFIG_FILE", "FONTCONFIG_PATH", "DBUS_SESSION_BUS_ADDRESS"
    ));
    private static final Set<String> HOST_ENV = new HashSet<>(Commands.join(DISCOVERY_ENV, List.of("LANG", "LC_ALL", "LC_CTYPE", "TZ", "XDG_RUNTIME_DIR")));
    private static final List<String> CONFIG_FILES = List.of(
        "gtk-3.0/settings.ini", "gtk-3.0/gtk.css", "gtk-3.0/colors.css",
        "gtk-4.0/settings.ini", "gtk-4.0/gtk.css", "gtk-4.0/colors.css",
        "gtkrc", "gtkrc-2.0", "kdeglobals", "breezerc", "qt5ct/qt5ct.conf", "qt6ct/qt6ct.conf"
    );
    private static final List<String> QT = List.of("styles", "platformthemes", "iconengines"), DATA = List.of("icons", "themes", "fonts", "color-schemes");
    private static final Map<String, List<String>> SETTINGS = Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
        "org.gnome.desktop.interface", List.of(
            "font-name", "document-font-name", "monospace-font-name", "gtk-theme", "icon-theme",
            "cursor-theme", "cursor-size", "color-scheme", "text-scaling-factor", "enable-animations",
            "font-antialiasing", "font-hinting", "font-rgba-order"
        ),
        "org.gnome.desktop.wm.preferences", List.of("button-layout")
    )));
    
    public record Supplement(Map<String, String> files, List<String> readOnly, Map<String, String> environment, List<String> runtimeRoots) {
        
        public Supplement {
            files = Map.copyOf(files);
            readOnly = List.copyOf(readOnly);
            environment = Map.copyOf(environment);
            runtimeRoots = List.copyOf(runtimeRoots);
        }
        
        @Json.Input private static final class Input {
            
            Map<String, String> files = Map.of(), environment = Map.of();
            List<String> readOnly = List.of(), runtimeRoots = List.of();
        }
        
        public static Supplement read(JsonElement value) {
            Input input = value.isJsonNull() ? new Input() : decode(value, Input.class);
            return new Supplement(input.files, input.readOnly, input.environment, input.runtimeRoots);
        }
        
    }
    
    public record Appearance(
        Map<String, String> files, List<Binding> links, Map<String, String> environment,
        List<String> runtimeRoots, List<String> readOnly, String settings
    ) {
        
        public Appearance {
            files = Map.copyOf(files);
            links = List.copyOf(links);
            environment = Map.copyOf(environment);
            runtimeRoots = List.copyOf(runtimeRoots);
            readOnly = List.copyOf(readOnly);
        }
        
        public static Appearance empty() { return new Appearance(Map.of(), List.of(), Map.of(), List.of(), List.of(), ""); }
        
    }
    
    public static final class Selection {
        
        private final String home;
        private final Map<String, String> files = new LinkedHashMap<>();
        private final Set<Binding> links = new TreeSet<>();
        private final Set<String> roots = new TreeSet<>();
        private final Set<String> dataTrees = new HashSet<>(), codeTrees = new HashSet<>();
        private final Storage.Provenance provenance;
        public Selection(String home, Storage.Provenance provenance) {
            this.home = Fs.absolute(home);
            this.provenance = provenance;
        }
        
        Appearance appearance(Supplement supplement, Map<String, String> environment, String settings) {
            Map<String, String> selected = new LinkedHashMap<>(files), exported = new LinkedHashMap<>(environment);
            selected.putAll(supplement.files);
            exported.putAll(supplement.environment);
            return new Appearance(
                selected, new ArrayList<>(links), exported,
                Commands.join(new ArrayList<>(roots), supplement.runtimeRoots), supplement.readOnly, settings
            );
        }
        
        public void file(String input) throws Exception {
            String path = Fs.absolute(input);
            var resource = Resources.resolve(path, provenance::immutable, true);
            if (resource == null) return;
            require(Files.isRegularFile(Path.of(resource.source())), "Appearance file is not a regular file: " + path);
            if (files.entrySet().stream().anyMatch(e -> path.startsWith(e.getKey() + "/") && Files.isDirectory(Path.of(e.getValue())))) return;
            require(resource.source().equals(path) || provenance.immutable(resource.source()), "Unsafe appearance symlink: " + path);
            files.put(path, resource.source());
        }
        
        public void tree(String path) throws Exception { tree(path, false); }
        
        public void tree(String input, boolean code) throws Exception {
            String path = Fs.absolute(input);
            var resource = Resources.resolve(path, provenance::immutable, true);
            if (resource == null) return;
            String source = resource.source();
            for (String selected : List.of(path, source)) {
                require(
                    !Set.of(
                        "/", "/home", "/root", "/etc", "/run", "/var", "/tmp", "/usr",
                        "/usr/share", "/usr/local/share", "/run/current-system/sw"
                    ).contains(selected)
                        && !Fs.under(home, selected) && !provenance.boundary(selected),
                    "Refusing broad appearance resource: " + path
                );
            }
            require(Files.isDirectory(Path.of(source)), "Appearance resource is not a directory: " + path);
            if (!source.equals(path)) checkLink(path, source, code, provenance);
            files.put(path, source);
            Set<String> visited = code ? codeTrees : dataTrees;
            if (visited.stream().anyMatch(root -> Fs.under(source, root))) return;
            var found = Resources.dependencies(List.of(resource), provenance::immutable, (item, root) -> {
                Path link = Files.readSymbolicLink(Path.of(item));
                // Downward relative links need no extra mount: their whole tree
                // is already selected. Any escape must pass another symlink,
                // which this walk inspects independently. Never normalize '..'.
                if (!link.isAbsolute() && StreamSupport.stream(link.spliterator(), false).noneMatch(p -> p.toString().equals(".."))) return null;
                var target = Resources.resolve(item, provenance::immutable, true);
                if (target == null) return null;
                if (!link.isAbsolute() && target.source().startsWith(root + "/")
                    && target.aliases().stream().allMatch(a -> a.startsWith(root + "/"))
                ) return null;
                checkLink(item, target.source(), code, provenance);
                return target;
            }, code ? entry -> {
                if (Pattern.compile("\\.so(?:\\.|$)").matcher(Fs.name(entry)).find() && Files.isRegularFile(entry)) {
                    String target = Fs.real(entry.toString());
                    require(Pattern.compile("\\.so(?:\\.|$)").matcher(target).find(), "Appearance library link is not a library: " + entry);
                    roots.add(target);
                }
            } : null, visited);
            found.files().forEach(b -> files.put(b.destination(), b.source()));
            links.addAll(found.links());
        }
        
        public static void checkLink(String path, String target, boolean code, Storage.Provenance provenance) {
            checkLink(path, target, code, Files.isRegularFile(Path.of(target)), provenance);
        }
        
        static void checkLink(String path, String target, boolean code, boolean file, Storage.Provenance provenance) {
            boolean data = Pattern.compile("/(?:share/(?:icons|themes|fonts|color-schemes|pixmaps)|etc/fonts)(?:/|$)").matcher(target).find();
            boolean toolkit = Pattern.compile("/lib/(?:gtk-[^/]+|qt-[^/]+/plugins/(?:styles|platformthemes|iconengines))(?:/|$)").matcher(target).find();
            boolean dataFile = file && Pattern.compile("(?i)\\.(conf|dtd|png|svg|xpm|ico|cur|ttf|otf|woff|woff2)$").matcher(target).find();
            boolean library = code && file && Pattern.compile("\\.so(?:\\.|$)").matcher(target).find();
            require(
                provenance.immutable(target) && (data || dataFile || code && toolkit || library),
                "Unsafe appearance symlink: " + path + " -> " + target
            );
        }
        
    }
    
    public static List<String> paths(String value, String home) {
        return Arrays.stream(value.split(":")).filter(s -> !s.isEmpty())
            .map(s -> Fs.resolve(s, home)).distinct().toList();
    }
    
    public static String userDirectories(String downloads) {
        return "XDG_DOWNLOAD_DIR=\"" + downloads.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\"\n";
    }
    
    public static String settings(PlatformConfig config, Session.Environment context) throws Exception {
        Map<String, String> env = new HashMap<>(context.values());
        env.putAll(Map.of(
            "GSETTINGS_SCHEMA_DIR", config.path(DESKTOP_SCHEMAS),
            "GIO_EXTRA_MODULES", config.path(DCONF_MODULES), "GSETTINGS_BACKEND", "dconf"
        ));
        List<String> lines = new ArrayList<>();
        // Stable order makes cohort fingerprints independent of map iteration salt.
        for (String schema : new TreeSet<>(SETTINGS.keySet())) {
            List<String> names = SETTINGS.get(schema);
            lines.add("[" + schema.replace('.', '/') + "]");
            var result = Commands.result(List.of(config.path(GSETTINGS), "list-recursively", schema), env, true);
            if (result.code() != 0) continue;
            Map<String, String> selected = new HashMap<>();
            for (String row : result.output().lines().toList()) {
                String[] fields = row.split("\\s+", 3);
                if (fields.length == 3 && fields[0].equals(schema) && names.contains(fields[1])) selected.put(fields[1], fields[2]);
            }
            for (String name : names) if (selected.containsKey(name)) lines.add(name + "=" + selected.get(name));
        }
        return String.join("\n", lines) + "\n";
    }
    
    public static Appearance discover(PlatformConfig config, Session.Environment session, Storage.Provenance provenance) throws Exception {
        Supplement supplement = Supplement.read(config.metadata("desktop"));
        Map<String, String> input = new HashMap<>(session.values());
        input.putAll(supplement.environment);
        input.keySet().retainAll(HOST_ENV);
        Session.Environment context = new Session.Environment(input);
        Map<String, String> env = context.values();
        String home = context.session().home();
        String configHome = env.getOrDefault("XDG_CONFIG_HOME", home + "/.config"), dataHome = env.getOrDefault("XDG_DATA_HOME", home + "/.local/share");
        List<String> configDirs = paths(env.getOrDefault("XDG_CONFIG_DIRS", "/etc/xdg"), home);
        List<String> dataDirs = paths(env.getOrDefault("XDG_DATA_DIRS", "/usr/local/share:/usr/share:/run/current-system/sw/share"), home);
        Selection resources = new Selection(home, provenance);
        Map<String, String> exported = new LinkedHashMap<>();
        for (String key : APPEARANCE_ENV) if (env.containsKey(key)) exported.put(key, env.get(key));
        exported.putAll(Map.of(
            "XDG_CONFIG_HOME", configHome, "XDG_DATA_HOME", dataHome,
            "XDG_CONFIG_DIRS", String.join(":", configDirs), "XDG_DATA_DIRS", String.join(":", dataDirs),
            "GTK_USE_PORTAL", "0", "XDG_SESSION_TYPE", "wayland"
        ));
        for (String dir : Commands.join(List.of(configHome), configDirs)) {
            for (String name : CONFIG_FILES) {
                resources.file(dir + "/" + name);
                resources.file(dir + "/kdedefaults/" + name);
            }
            for (String name : List.of("qt5ct", "qt6ct")) resources.tree(dir + "/" + name + "/colors");
        }
        for (String name : List.of(".gtkrc-2.0", ".gtkrc")) resources.file(home + "/" + name);
        for (String dir : Commands.join(List.of(dataHome), dataDirs)) for (String name : DATA) resources.tree(dir + "/" + name);
        for (String name : List.of(".icons", ".themes", ".fonts")) resources.tree(home + "/" + name);
        for (String key : List.of("GTK2_RC_FILES", "GTK_RC_FILES", "XCURSOR_PATH", "GTK_PATH", "QT_PLUGIN_PATH")) {
            List<String> selected = paths(env.getOrDefault(key, ""), home);
            for (String path : selected) {
                switch (key) {
                    case "GTK2_RC_FILES", "GTK_RC_FILES" -> resources.file(path);
                    case "XCURSOR_PATH" -> resources.tree(path);
                    case "QT_PLUGIN_PATH" -> { for (String category : QT) resources.tree(path + "/" + category, true); }
                    default -> {
                        for (String category : List.of("modules", "engines", "immodules")) {
                            resources.tree(path + "/" + category, true);
                            if (Files.isDirectory(Path.of(path))) {
                                try (var versions = Files.newDirectoryStream(Path.of(path), "[0-9]*")) {
                                    for (Path version : versions) resources.tree(version.resolve(category).toString(), true);
                                }
                            }
                        }
                    }
                }
            }
            if (!selected.isEmpty() || env.containsKey(key) && key.endsWith("RC_FILES")) exported.put(key, String.join(":", selected));
        }
        String fontFile = env.getOrDefault("FONTCONFIG_FILE", "/etc/fonts/fonts.conf");
        Path fontParent = Path.of(fontFile).getParent();
        List<String> fontDirs = paths(env.getOrDefault("FONTCONFIG_PATH", fontParent == null ? "." : fontParent.toString()), home);
        resources.file(fontFile);
        for (String dir : Commands.join(fontDirs, List.of(configHome + "/fontconfig"))) {
            for (String name : List.of("fonts.conf", "local.conf", "fonts.dtd")) resources.file(dir + "/" + name);
            resources.tree(dir + "/conf.d");
        }
        resources.file(home + "/.fonts.conf");
        Map<String, String> fontEnv = new HashMap<>(env);
        fontEnv.put("LC_ALL", "C");
        String confs = Commands.output(List.of(Path.of(config.path(FC_LIST)).resolveSibling("fc-conflist").toString()), fontEnv);
        for (String line : confs.lines().toList()) {
            var match = Pattern.compile("[+-] (/.*): .*").matcher(line);
            require(match.matches(), "Unexpected fc-conflist output: " + line);
            resources.file(required(match.group(1), "Unexpected fc-conflist output: " + line));
        }
        for (String font : new TreeSet<>(Commands.output(List.of(config.path(FC_LIST), "--format=%{file}\\n"), env).lines().toList())) {
            int marker = font.indexOf("/share/fonts/");
            if (marker >= 0) resources.tree(font.substring(0, marker) + "/share/fonts"); else resources.file(font);
        }
        if (Files.exists(Path.of(fontFile))) exported.putAll(Map.of("FONTCONFIG_FILE", fontFile, "FONTCONFIG_PATH", String.join(":", fontDirs)));
        return resources.appearance(supplement, exported, settings(config, context));
    }
    
}