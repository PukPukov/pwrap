package ru.pukpukov.pwrap.core;

import module java.base;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class Commands {
    
    @FunctionalInterface public interface Query { String output(List<String> argv) throws Exception; }
    
    /** Raw argv sink, like Posix.spawn: policy decides the executable/argument authority.
     * Never interprets/escapes shell text. This is not a general-purpose sanitizer. */
    @SuppressWarnings("tainting:argument")
    public static ProcessBuilder process(List<String> argv) { return new ProcessBuilder(argv); }
    
    @SafeVarargs public static List<String> join(List<String>... groups) {
        List<String> result = new ArrayList<>(); for (List<String> group : groups) result.addAll(group); return result;
    }
    
    public static List<String> args(String... parts) { return new ArrayList<>(List.of(parts)); }
    
    public record Result(int code, String output) {
        public String checked(List<String> command) throws IOException {
            if (code != 0) throw new IOException("Command failed (" + code + "): " + command);
            return output;
        }
    }
    
    public static Result result(List<String> command, Map<String, String> environment, boolean quietError) throws IOException, InterruptedException {
        ProcessBuilder builder = process(command).redirectError(quietError ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.INHERIT);
        builder.environment().clear(); builder.environment().putAll(environment); Process process = builder.start();
        try {
            process.getOutputStream().close();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), text);
        } finally { process.destroy(); }
    }
    
    public static String output(List<String> command, Map<String, String> environment) throws IOException, InterruptedException {
        return result(command, environment, false).checked(command);
    }
    
    public static String output(List<String> command) throws IOException, InterruptedException { return run(command, null, true); }
    
    public static String run(List<String> command, @Nullable String input, boolean check) throws IOException, InterruptedException {
        Process process = process(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            try (var writer = process.getOutputStream()) { if (input != null) writer.write(input.getBytes(StandardCharsets.UTF_8)); }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (check && code != 0) throw new IOException("Command failed (" + code + "): " + command);
            return output;
        } finally { process.destroy(); }
    }
    
    public static int call(List<String> command) throws IOException, InterruptedException {
        Process process = process(command).inheritIO().start();
        try { return process.waitFor(); } finally { process.destroy(); }
    }
    
    public static void check(List<String> command) throws IOException, InterruptedException {
        int code = call(command); if (code != 0) throw new IOException("Command failed (" + code + "): " + command);
    }
    
    public static String executable(String command) throws IOException {
        if (command.contains("/")) { String real = Fs.real(Fs.absolute(command)); if (Files.isRegularFile(Path.of(real))) return real; }
        else for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path path = Path.of(directory, command); if (Files.isRegularFile(path) && Files.isExecutable(path)) return Fs.real(path.toString());
        }
        throw new IOException("Executable not found: " + command);
    }
    
}