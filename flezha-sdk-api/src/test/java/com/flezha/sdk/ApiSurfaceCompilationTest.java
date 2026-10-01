package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The api module's surface, asserted by compiling user code against it: a
 * deleted spelling must not resolve, and the spelling that replaced it must.
 * Mirrors the Rust SDK's {@code OldApiNotImportable}.
 */
class ApiSurfaceCompilationTest {

    /** Class output for the sources that do compile; JUnit removes it. */
    @TempDir
    static Path classOutput;

    @ParameterizedTest(name = "{0} does not resolve")
    @ValueSource(
            strings = {
                "com.flezha.sdk.FactoryRegistry",
                "com.flezha.sdk.RunnerBuilder",
            })
    void deletedTypesDoNotResolve(String type) {
        Compilation result = compile(usesImport(type));

        assertThat(result.ok()).as("import %s should not resolve", type).isFalse();
        assertThat(result.diagnostics())
                .as("the failure names the missing type")
                .contains(type);
    }

    @Test
    @DisplayName("the one base and its settings tree resolve from one package")
    void theOneBaseAndItsTreeShareOnePackage() {
        assertThat(
                        compiles(
                                """
                        package nowhere;
                        import com.flezha.sdk.operator.Operator;
                        import java.time.Duration;
                        public final class UsesLegacyApi {
                            Operator.Run steps = new Operator.Run.Steps(
                                    Operator.Each.CHAINED, Operator.OnFailure.KEEP_PROGRESS);
                            Operator.Run flow = new Operator.Run.Flow(new Operator.Exactly.EffectivelyOnce()
                                    .withAck(new Operator.Ack.Checkpoint().withEvery(500))
                                    .withCorrectionMs(60_000));
                            Operator.Durable durable = new Operator.Durable.Local(Duration.ofMinutes(10));
                        }
                        """))
                .as("the base and every node of the tree resolve from com.flezha.sdk.operator")
                .isTrue();
    }

    /** The misplaced-setting rows of {@code test/fixtures/reference_topologies.txt}, one fixture each. */
    @ParameterizedTest(name = "{0} does not compile")
    @ValueSource(
            strings = {
                "each_under_flow",
                "ack_under_at_least_once",
                "ack_under_steps",
                "correction_under_steps",
                "exactly_under_steps",
                "sleep_after_in_memory",
                "kind_literal",
            })
    void aSettingUnderTheWrongParentDoesNotCompile(String row) throws IOException {
        String source = Files.readString(Path.of("src/test/resources/compile_fail", row + ".java"));
        String refusal = row.equals("kind_literal") ? "topology.invalid_setting" : "topology.unknown_field";

        assertThat(source)
                .as("the fixture names the refusal the row earns elsewhere")
                .startsWith("// " + refusal + ":");
        assertThat(compile(source).ok()).as("the fixture:\n" + source).isFalse();
    }

    @Test
    @DisplayName("the compile-fail fixtures' class compiles with its settings in their place")
    void theFixtureClassCompilesWithItsSettingsInPlace() {
        assertThat(
                        compiles(
                                operatorWith(
                                        "",
                                        """
                @Override public Run run() {
                    return new Run.Flow(new Exactly.EffectivelyOnce().withAck(new Ack.Checkpoint()).withCorrectionMs(5_000));
                }
                @Override public Durable durable() { return new Durable.Local(java.time.Duration.ofSeconds(1)); }
                """)))
                .as("the control: only the misplaced setting decides a fixture's outcome")
                .isTrue();
    }

    @Test
    @DisplayName("user code calling Flezha.newRunner fails to compile")
    void callingNewRunnerFails() {
        assertDoesNotCompile(
                """
                package nowhere;
                import com.flezha.sdk.Flezha;
                public final class UsesLegacyApi {
                    void foo() { Flezha.newRunner("ns"); }
                }
                """);
    }

    @Test
    @DisplayName("user code calling the removed output / alsoOutput egress fails to compile")
    void callingRemovedEgressMethodsFails() {
        // Control first: without it a classpath that resolved nothing would
        // make every refusal below vacuously true.
        assertThat(compiles(usesStream("s.sink(\"x\");")))
                .as("the replacement spelling must compile — otherwise the refusals prove nothing")
                .isTrue();

        assertDoesNotCompile(usesStream("s.output(\"x\");"));
        assertDoesNotCompile(usesStream("s.output(\"x\", v -> v.getBytes());"));
        assertDoesNotCompile(usesStream("s.alsoOutput(\"x\");"));
        assertDoesNotCompile(usesStream("s.alsoOutput(\"x\", v -> v.getBytes());"));
    }

    @Test
    @DisplayName("The retry knobs are gone from the interface")
    void theRetryKnobsAreGoneFromTheInterface() {
        assertThat(compiles(operatorWith("c.input(String.class, \"in\").retryMax(2).retryBackoffMs(20);", "")))
                .as("the edge's retry declaration must compile, or the refusals prove nothing")
                .isTrue();

        assertDoesNotCompile(operatorWith("", "@Override public int retryAttempts() { return 1; }"));
        assertDoesNotCompile(operatorWith("", "@Override public java.time.Duration retryBackoff() { return null; }"));
        assertDoesNotCompile(operatorWith("", "@Override public int barrierRetryAttempts() { return 1; }"));
    }

    @Test
    @DisplayName("a graph written in a body named tasklets does not compile: channels is abstract on the base")
    void aBodyNamedTaskletsDoesNotCompile() {
        assertDoesNotCompile(
                """
                package nowhere;
                import com.flezha.sdk.channel.ChannelBuilder;
                import com.flezha.sdk.operator.Operator;
                public final class UsesLegacyApi implements Operator {
                    @Override public String factoryId() { return "legacy"; }
                    public void tasklets(ChannelBuilder c) {}
                }
                """);
    }

    @Test
    @DisplayName("The javadoc speaks in the graph vocabulary")
    void theJavadocSpeaksInTheGraphVocabulary() throws IOException {
        List<String> retired =
                List.of("ordered steps", "step sequence", "resumes at the step", "between two steps", "step boundary");
        List<String> found = new ArrayList<>();
        for (Path tree : List.of(Path.of("src/main/java"), Path.of("../flezha-sdk-runtime/src/main/java"))) {
            try (Stream<Path> walk = Files.walk(tree)) {
                for (Path source :
                        walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source).replaceAll("\\s*\\n\\s*(\\*\\s*|//\\s*)?", " ");
                    retired.stream().filter(text::contains).forEach(phrase -> found.add(source + ": " + phrase));
                }
            }
        }
        assertThat(found)
                .as("an operator that runs in steps is a graph committed stage by stage")
                .isEmpty();
    }

    /** An operator class whose graph body is {@code graph} and whose remaining members are {@code members}. */
    private static String operatorWith(String graph, String members) {
        return """
                package nowhere;
                import com.flezha.sdk.channel.ChannelBuilder;
                import com.flezha.sdk.operator.Operator;
                public final class UsesLegacyApi implements Operator {
                    @Override public String factoryId() { return "legacy"; }
                    @Override public void channels(ChannelBuilder c) { %s }
                    %s
                }
                """
                .formatted(graph, members);
    }

    /** One import of {@code type}, the only thing under test. */
    private static String usesImport(String type) {
        return """
                package nowhere;
                import %s;
                public final class UsesLegacyApi { }
                """
                .formatted(type);
    }

    /** One `Stream<String>` in scope, `body` the only thing under test. */
    private static String usesStream(String body) {
        return """
                package nowhere;
                import com.flezha.sdk.channel.Stream;
                public final class UsesLegacyApi {
                    void foo(Stream<String> s) { %s }
                }
                """
                .formatted(body);
    }

    private static void assertDoesNotCompile(String src) {
        assertThat(compiles(src))
                .as("deleted API must not resolve; source:\n" + src)
                .isFalse();
    }

    private static boolean compiles(String src) {
        return compile(src).ok();
    }

    /** Outcome of one in-process compile: whether it succeeded, and what javac said. */
    private record Compilation(boolean ok, String diagnostics) {}

    /**
     * Compile one source in-process against this module's own classpath, so a
     * reference to a live SDK type resolves and only the tested spelling
     * decides the outcome. The one compiler invocation in this file.
     */
    private static Compilation compile(String src) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        boolean ok = compiler.getTask(
                        new PrintWriter(err),
                        null,
                        null,
                        List.of(
                                "-proc:none",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classOutput.toString()),
                        null,
                        List.of(new SourceFile("UsesLegacyApi.java", src)))
                .call();
        return new Compilation(ok, err.toString());
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String code;

        SourceFile(String name, String code) {
            super(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
