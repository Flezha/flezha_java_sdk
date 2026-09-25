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
                "com.flezha.sdk.UnboundedOperatorHandler",
                "com.flezha.sdk.FactoryRegistry",
                "com.flezha.sdk.RunnerBuilder",
                "com.flezha.sdk.operator.UnboundedOperatorRegistry",
                "com.flezha.sdk.operator.BoundedOperatorRegistry",
            })
    void deletedTypesDoNotResolve(String type) {
        Compilation result = compile(usesImport(type));

        assertThat(result.ok()).as("import %s should not resolve", type).isFalse();
        assertThat(result.diagnostics())
                .as("the failure names the missing type")
                .contains(type);
    }

    @Test
    @DisplayName("the bounded kind is not in a package of its own: com.flezha.sdk.bounded does not resolve")
    void theBoundedKindIsNotInAPackageOfItsOwn() {
        for (String type : List.of(
                "com.flezha.sdk.bounded.BoundedOperator",
                "com.flezha.sdk.bounded.BoundedOperatorConfigurationException",
                "com.flezha.sdk.bounded.LoopMode",
                "com.flezha.sdk.bounded.RollbackMode")) {
            assertDoesNotCompile(usesImport(type));
        }
    }

    @Test
    @DisplayName("both kinds and the base share one package, the bounded supporting types with them")
    void bothKindsAndTheBaseShareOnePackage() {
        assertThat(
                        compiles(
                                """
                        package nowhere;
                        import com.flezha.sdk.operator.LoopMode;
                        import com.flezha.sdk.operator.Operator;
                        import com.flezha.sdk.operator.RollbackMode;
                        import com.flezha.sdk.operator.UnboundedOperator;
                        import com.flezha.sdk.operator.BoundedOperator;
                        import com.flezha.sdk.operator.BoundedOperatorConfigurationException;
                        public final class UsesLegacyApi {
                            // Both kinds are the base, so one return type holds either.
                            Operator eitherKind(UnboundedOperator s, BoundedOperator w, boolean b) {
                                return b ? s : w;
                            }

                            LoopMode loop;
                            RollbackMode rollback;
                            BoundedOperatorConfigurationException refusal;
                        }
                        """))
                .as("the base, both kinds and the bounded supporting types resolve from one package")
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
        assertThat(compiles(usesUnbounded("s.sink(\"x\");")))
                .as("the replacement spelling must compile — otherwise the refusals prove nothing")
                .isTrue();

        assertDoesNotCompile(usesUnbounded("s.output(\"x\");"));
        assertDoesNotCompile(usesUnbounded("s.output(\"x\", v -> v.getBytes());"));
        assertDoesNotCompile(usesUnbounded("s.alsoOutput(\"x\");"));
        assertDoesNotCompile(usesUnbounded("s.alsoOutput(\"x\", v -> v.getBytes());"));
    }

    @Test
    @DisplayName("a sealed channel accepts no further declaration")
    void sealedRefusesFurtherDeclaration() {
        assertThat(compiles(usesUnbounded("s.sink(\"x\").sealed();")))
                .as("sealing a sink is the supported terminator")
                .isTrue();

        assertDoesNotCompile(usesUnbounded("s.sink(\"x\").sealed().named(\"y\");"));
        assertDoesNotCompile(usesUnbounded("s.sink(\"x\").sealed().keyed();"));
    }

    @Test
    @DisplayName("The retry knobs are gone from the interface")
    void theRetryKnobsAreGoneFromTheInterface() {
        assertThat(compiles(boundedWith("c.input(String.class, \"in\").retryMax(2).retryBackoffMs(20);", "")))
                .as("the edge's retry declaration must compile, or the refusals prove nothing")
                .isTrue();

        assertDoesNotCompile(boundedWith("", "@Override public int retryAttempts() { return 1; }"));
        assertDoesNotCompile(boundedWith("", "@Override public java.time.Duration retryBackoff() { return null; }"));
        assertDoesNotCompile(boundedWith("", "@Override public int barrierRetryAttempts() { return 1; }"));
    }

    @Test
    @DisplayName("a bounded graph written in a body named tasklets does not compile: channels is abstract on the base")
    void aBoundedBodyNamedTaskletsDoesNotCompile() {
        assertDoesNotCompile(
                """
                package nowhere;
                import com.flezha.sdk.channel.ChannelBuilder;
                import com.flezha.sdk.operator.BoundedOperator;
                public final class UsesLegacyApi implements BoundedOperator {
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
                .as("a bounded operator is a graph committed stage by stage")
                .isEmpty();
    }

    /** A bounded class whose graph body is {@code graph} and whose remaining members are {@code members}. */
    private static String boundedWith(String graph, String members) {
        return """
                package nowhere;
                import com.flezha.sdk.channel.ChannelBuilder;
                import com.flezha.sdk.operator.BoundedOperator;
                public final class UsesLegacyApi implements BoundedOperator {
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
    private static String usesUnbounded(String body) {
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
