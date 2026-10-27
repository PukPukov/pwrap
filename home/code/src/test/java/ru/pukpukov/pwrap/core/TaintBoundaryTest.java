package ru.pukpukov.pwrap.core;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import static org.junit.jupiter.api.Assertions.*;

/** Compile both routes with the real processor: raw Gson output must not reach launch. */
@DisabledInNativeImage // Compiler contract is checked on the JVM, not by the native runtime.
class TaintBoundaryTest {
    final Path temp;
    TaintBoundaryTest(@TempDir Path temp) { this.temp = temp; }
    
    private record Compilation(int code, String output) {}
    
    private Compilation compile(String body) throws Exception {
        Path repo = Path.of(System.getProperty("maven.repo.local", System.getProperty("user.home") + "/.m2/repository"));
        String version = "4.2.3";
        Path checker = repo.resolve("org/checkerframework/checker/" + version + "/checker-" + version + ".jar");
        Path qual = repo.resolve("org/checkerframework/checker-qual/" + version + "/checker-qual-" + version + ".jar");
        Path util = repo.resolve("org/checkerframework/checker-util/" + version + "/checker-util-" + version + ".jar");
        Path gson = repo.resolve("com/google/code/gson/gson/2.13.2/gson-2.13.2.jar");
        Path source = temp.resolve("Boundary.java");
        Files.writeString(
            source, """
            import com.google.gson.JsonObject;
            import ru.pukpukov.pwrap.Launch;
            import ru.pukpukov.pwrap.Config;
            import ru.pukpukov.pwrap.core.*;
            class Boundary {
                %s
            }
            """.formatted(body)
        );
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/javac").toString()));
        Files.readAllLines(Path.of(".mvn/jvm.config")).stream().filter(s -> !s.isBlank()).forEach(s -> command.add("-J" + s));
        command.addAll(List.of(
            "-cp", "target/classes:" + qual + ":" + gson,
            "-processorpath", checker + ":" + qual + ":" + util,
            "-processor",
            "org.checkerframework.checker.nullness.NullnessChecker,org.checkerframework.checker.formatter.FormatterChecker," +
                "org.checkerframework.common.value.ValueChecker,org.checkerframework.checker.tainting.TaintingChecker",
            "-d", temp.toString(), source.toString()
        ));
        Path log = temp.resolve("compiler.log");
        Process compiler = Commands.process(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(compiler.waitFor(30, TimeUnit.SECONDS), "Checker compile timed out"); }
        finally { if (compiler.isAlive()) compiler.destroyForcibly().waitFor(); }
        return new Compilation(compiler.exitValue(), Files.readString(log));
    }
    
    private static String launch(String expression) {
        return "static void launch(JsonObject input, Config config, Lifetime lifetime) throws Exception { Launch.prepare(" +
            expression + ", config, lifetime); }";
    }
    
    private void rejected(String body, String diagnostic) throws Exception {
        Compilation result = compile(body);
        assertNotEquals(0, result.code, "Checker accepted invalid code");
        assertTrue(result.output.contains(diagnostic), result.output);
    }
    
    @Test void nullness_processor_rejects_a_nullable_dereference() throws Exception {
        rejected(
            "static int broken(@org.checkerframework.checker.nullness.qual.Nullable String value) { return value.length(); }",
            "[dereference.of.nullable]"
        );
    }
    
    @Test void formatter_processor_rejects_wrong_argument_types() throws Exception {
        rejected("static String broken() { return String.format(\"%d\", \"not a number\"); }", "[argument]");
    }
    
    @Test void value_processor_rejects_an_out_of_range_constant() throws Exception {
        rejected("@org.checkerframework.common.value.qual.IntRange(from=1, to=3) int value = 4;", "[assignment]");
    }
    
    @Test void normalization_is_required_and_checked_not_just_annotated() throws Exception {
        Compilation accepted = compile(launch("Policy.read(input)"));
        assertEquals(0, accepted.code, accepted.output);
        Compilation rejected = compile(launch("Json.decode(input, Policy.class)"));
        assertNotEquals(0, rejected.code, "Unvalidated JSON bypassed the trust boundary");
        assertTrue(
            rejected.output.contains("[argument]") && rejected.output.contains("@Tainted Policy") &&
                rejected.output.contains("@Untainted Policy"),
            rejected.output
        );
    }
    
}