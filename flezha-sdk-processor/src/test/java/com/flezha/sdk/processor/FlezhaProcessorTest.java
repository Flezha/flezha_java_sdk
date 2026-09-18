package com.flezha.sdk.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Drives {@link FlezhaProcessor} over malformed sources and asserts its diagnostics. */
class FlezhaProcessorTest {

    @TempDir
    Path out;

    @Test
    void rejectsEntityKeyAndNamesItsReplacement() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                import java.util.Optional;
                @Procedure(id = "bad")
                public class Bad {
                    @EntityKey public Optional<String> s(String in) { return Optional.empty(); }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Bad", code)).anyMatch(m -> m.contains(".keyBy(f)"));
    }

    @Test
    void rejectsInitStateOnStatelessProcedure() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.InitState;
                import com.flezha.sdk.authoring.Procedure;
                import java.util.Optional;
                @Procedure(id = "bad_init")
                public class BadInit {
                    @InitState public String initState(Optional<String> key) { return ""; }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.BadInit", code)).anyMatch(m -> m.contains("@InitState requires a stateful run"));
    }

    @Test
    void rejectsNonPublicStateField() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.State;
                import java.util.ArrayList;
                import java.util.List;
                @State
                public class BadState {
                    private List<String> xs = new ArrayList<>();
                }
                """;
        assertThat(diagnose("t.BadState", code)).anyMatch(m -> m.contains("public, non-final fields"));
    }

    @Test
    void rejectsUnannotatedMapField() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.State;
                import java.util.HashMap;
                import java.util.Map;
                @State
                public class MapState {
                    public Map<String, Double> last = new HashMap<>();
                }
                """;
        assertThat(diagnose("t.MapState", code)).anyMatch(m -> m.contains("@Journal"));
    }

    @Test
    void rejectsJournalTypeMismatch() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Journal;
                import com.flezha.sdk.authoring.State;
                import java.util.HashMap;
                import java.util.Map;
                @State
                public class MismatchState {
                    @Journal(Journal.Mode.MAP_VEC)
                    public Map<String, Double> bad = new HashMap<>();
                }
                """;
        assertThat(diagnose("t.MismatchState", code)).anyMatch(m -> m.contains("Map<K, List<U>>"));
    }

    @Test
    void keyOptInGeneratesAKeyedEmit() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                @Procedure(id = "keyed")
                public class Plain {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Plain", "t.PlainProcedure", code)).contains("ctx.emit(out);");

        String keyed =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                @Procedure(id = "keyed", key = "k")
                public class Keyed {
                    public static String k(String out) { return out; }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Keyed", "t.KeyedProcedure", keyed)).contains("ctx.emitKeyed(t.Keyed.k(out), out);");
    }

    /**
     * The generator used to write {@code .named(x).sink(x)} because a sink
     * named a plugin rather than a channel, so sugared and hand-written code
     * shipped different wire names for the same operative.
     */
    @Test
    void theGeneratedChannelsNamesTheChannelWithTheSinkAndNothingElse() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "sugar", sink = "items_output")
                public class Sugared {
                    public String run(String in) { return in; }
                }
                """;
        String source = generated("t.Sugared", "t.SugaredStreamOperative", code);

        assertThat(source).contains(".sink(\"items_output\")");
        assertThat(source).doesNotContain(".named(");
    }

    @Test
    void contextParameterRunEmitsThroughTheBody() {
        String stateless =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                import com.flezha.sdk.procedure.ProcedureCtx;
                @Procedure(id = "fanout", emits = String.class)
                public class Fanout {
                    public void run(String in, ProcedureCtx ctx) { ctx.emit(in); ctx.emit(in); }
                }
                """;
        String generated = generated("t.Fanout", "t.FanoutProcedure", stateless);
        // The body owns every emit: the wrapper passes the context and adds none.
        assertThat(generated).contains("impl.run(input, ctx);");
        assertThat(generated).doesNotContain("ctx.emit(out)");
        // `emits` lands as the Output type argument, which is where the runtime
        // reads it from — the tokens are not restated.
        assertThat(generated)
                .contains("implements Procedure<java.lang.String, com.flezha.sdk.state.Stateless,"
                        + " java.lang.String, com.flezha.sdk.state.NoChange, WallClock, Void>");
        assertThat(generated).doesNotContain("outputType()");

        String stateful =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                import com.flezha.sdk.procedure.ProcedureCtx;
                import com.flezha.sdk.state.NoChange;
                import com.flezha.sdk.state.StateHandle;
                @Procedure(id = "fanout2", emits = String.class)
                public class Fanout2 {
                    public void run(String in, StateHandle<String, NoChange> state, ProcedureCtx ctx) {
                        ctx.emit(in);
                    }
                }
                """;
        assertThat(generated("t.Fanout2", "t.Fanout2Procedure", stateful)).contains("impl.run(input, state, ctx);");
    }

    @Test
    void flatDequeFieldsGetSlidingWindowMutators() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.State;
                import java.util.ArrayDeque;
                import java.util.Deque;
                @State
                public class WindowState {
                    public Deque<Double> ticks = new ArrayDeque<>();
                }
                """;
        String change = generated("t.WindowState", "t.WindowStateChange", code);
        assertThat(change).contains("pushBackTicks");
        assertThat(change).contains("popFrontTicks");
        assertThat(change).contains(".addLast(c.value)");
        assertThat(change).contains("pollFirst()");
    }

    @Test
    void theGeneratedEntryChannelKeepsItsDefaultName() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "default_entry", sink = "echo")
                public class DefaultEntry {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.DefaultEntry", "t.DefaultEntryStreamOperative", code))
                .contains("c.input(java.lang.String.class, \"input\")");
    }

    @Test
    void theAnnotationNamesTheGeneratedEntryChannel() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "named_entry", sink = "echo", input = "prices")
                public class NamedEntry {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.NamedEntry", "t.NamedEntryStreamOperative", code))
                .contains("c.input(java.lang.String.class, \"prices\")");
    }

    @Test
    void aShortFormWithNoSinkDeclaresNoPluginChannel() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "counter", input = "ticks")
                public class Counter {
                    public void run(String in) {}
                }
                """;
        String source = generated("t.Counter", "t.CounterStreamOperative", code);
        assertThat(source).contains("c.input(java.lang.String.class, \"ticks\").into(CounterProcedure.class);");
        assertThat(source).doesNotContain(".sink(");
    }

    @ParameterizedTest(name = "{0} with no id is refused")
    @ValueSource(strings = {"@StreamOperative(sink = \"out\")", "@WorkflowOperative(id = \"\", sink = \"out\")"})
    void anAnnotationWithNoIdIsRefused(String annotation) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                %s
                public class Upcase {
                    public String run(String in) { return in; }
                }
                """
                        .formatted(annotation);
        assertThat(diagnose("t.Upcase", code)).anyMatch(m -> m.contains("`id`") && m.contains("factory id"));
    }

    @Test
    void anAnnotatedClassImplementingAnOperativeInterfaceIsRefused() {
        String code =
                """
                package t;
                import com.flezha.sdk.channel.ChannelBuilder;
                @com.flezha.sdk.authoring.StreamOperative(id = "both", sink = "out")
                public class Both implements com.flezha.sdk.operative.StreamOperative {
                    @Override public String factoryId() { return "both"; }
                    @Override public void channels(ChannelBuilder c) {}
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Both", code))
                .anyMatch(m -> m.startsWith("ShortFormWithTopology") && m.contains("implements StreamOperative"));
    }

    @Test
    void aSinkOnAVoidRunIsRefused() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "drain", sink = "out")
                public class Drain {
                    public void run(String in) {}
                }
                """;
        assertThat(diagnose("t.Drain", code)).anyMatch(m -> m.startsWith("SinkWithoutOutput"));
    }

    @ParameterizedTest(name = "`{0}` on @Procedure is refused")
    @ValueSource(strings = {"sink = \"echo\"", "input = \"prices\"", "kind = \"stream\"", "onFull = \"drop_oldest\""})
    void anOperativeMemberOnAProcedureIsRefused(String member) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                @Procedure(id = "do_nothing", %s)
                public class DoNothing {
                    public String run(String in) { return in; }
                }
                """
                        .formatted(member);
        String name = member.substring(0, member.indexOf(' '));
        assertThat(diagnose("t.DoNothing", code))
                .anyMatch(m -> m.contains("`" + name + "`")
                        && m.contains("@StreamOperative")
                        && m.contains("@WorkflowOperative"));
    }

    /** fails when: a short form's entry-channel or procedure setting stops reaching what it describes. */
    @ParameterizedTest(name = "{0} carries capacity and onFull")
    @ValueSource(strings = {"StreamOperative", "WorkflowOperative"})
    void aShortFormCarriesItsEntryAndProcedureSettings(String kind) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @%s(id = "capped", capacity = 4, onFull = "drop_oldest")
                public class Capped {
                    public String run(String in) { return in; }
                }
                """
                        .formatted(kind);
        assertThat(generated("t.Capped", "t.Capped" + kind, code))
                .contains(".onFull(com.flezha.sdk.channel.OverflowPolicy.DROP_OLDEST).into(CappedProcedure.class)");
        assertThat(generated("t.Capped", "t.CappedProcedure", code)).contains("mailboxCapacity() { return 4; }");
    }

    /**
     * fails when: a refusal of the {@code run} shape or of a procedure member names an annotation
     * other than the one the author wrote. {@code %s} in the expected text is that annotation.
     */
    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("refusalsNamingTheAnnotation")
    void aRefusalNamesTheAnnotationTheAuthorWrote(String annotation, String members, String body, String message) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                import com.flezha.sdk.procedure.ProcedureCtx;
                import java.util.List;
                import java.util.Optional;
                %s(id = "bad"%s)
                public class Bad {
                    %s
                }
                """
                        .formatted(annotation, members, body);
        assertThat(diagnose("t.Bad", code)).anyMatch(m -> m.contains(message.formatted(annotation)));
    }

    static Stream<Arguments> refusalsNamingTheAnnotation() {
        String echo = "public String run(String in) { return in; }";
        String keyOf = "public static String k(String out) { return out; } ";
        List<Arguments> rows = new ArrayList<>();
        for (String annotation : List.of("@Procedure", "@StreamOperative", "@WorkflowOperative")) {
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "public String go(String in) { return in; }",
                    "%s requires exactly one public method named `run`"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "public String run(String a, String b, String c) { return a; }",
                    "%s `run` must be `Output run(Input)`"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "public Optional run(String in) { return Optional.of(in); }",
                    "%s `run` returning Optional must be parameterized as Optional<Output>"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "public String run(String in, String notAHandle) { return in; }",
                    "%s stateful `run` second parameter must be"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "public String run(String in, ProcedureCtx ctx) { return in; }",
                    "%s `run` with a `ProcedureCtx` parameter makes the body responsible for every emit"));
            rows.add(Arguments.of(
                    annotation,
                    ", key = \"k\", emits = String.class",
                    keyOf + "public void run(String in, ProcedureCtx ctx) { ctx.emit(in); }",
                    "%s(key = ...) conflicts with a `ProcedureCtx` parameter"));
            rows.add(Arguments.of(
                    annotation,
                    ", emits = String.class",
                    echo,
                    "%s(emits = ...) declares the output type of a `ProcedureCtx` `run`"));
            rows.add(Arguments.of(
                    annotation,
                    ", key = \"k\"",
                    keyOf + "public void run(String in) { }",
                    "%s(key = ...) requires a non-void return type; there is no emit to key"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "@EntitiesFor public List<String> route(String in) { return List.of(); } " + echo,
                    "static entity requires %s(keys = {...}) alongside the @EntitiesFor method"));
            rows.add(Arguments.of(
                    annotation, ", keys = {\"a\"}", echo, "%s(keys = {...}) requires a @EntitiesFor method"));
            if (!annotation.equals("@Procedure")) {
                rows.add(Arguments.of(
                        annotation,
                        ", onFull = \"drop_everything\"",
                        echo,
                        "%s onFull must be one of: drop_nothing, drop_newest, drop_oldest"));
            }
        }
        return rows.stream();
    }

    @Test
    void aClassCarryingTwoDeclarationsIsRefused() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @Procedure(id = "twice")
                @StreamOperative(id = "twice", sink = "out")
                public class Twice {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Twice", code)).anyMatch(m -> m.contains("@Procedure") && m.contains("@StreamOperative"));
    }

    @Test
    void aWorkflowShortFormGeneratesAWorkflowOperative() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @WorkflowOperative(id = "place", sink = "placed", input = "order")
                public class Place {
                    public String run(String in) { return in; }
                }
                """;
        String source = generated("t.Place", "t.PlaceWorkflowOperative", code);
        assertThat(source).contains("implements WorkflowOperative");
        assertThat(source).contains("public void procedures(ChannelBuilder c)");
        assertThat(source)
                .contains("c.input(java.lang.String.class, \"order\").into(PlaceProcedure.class).sink(\"placed\");");
    }

    @Test
    void writesNoAggregateRegistrationClass() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "alone", sink = "out")
                public class Alone {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generatedFiles("t.Alone", code))
                .containsExactlyInAnyOrder("t/AloneProcedure.java", "t/AloneStreamOperative.java");
    }

    @Test
    void aProcedureGeneratesNoOperative() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Procedure;
                @Procedure(id = "single_mode")
                public class SingleMode {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generatedFiles("t.SingleMode", code)).containsExactly("t/SingleModeProcedure.java");
    }

    @Test
    void keepsAQuoteInTheShortFormStringsExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "p\\"id", sink = "s\\"sink", input = "i\\"in")
                public class Quoted {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.Quoted", "t.QuotedStreamOperative", code)))
                .contains("p\"id", "s\"sink", "i\"in");
    }

    @Test
    void keepsABackslashInTheShortFormStringsExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @StreamOperative(id = "p\\\\nid", sink = "s\\\\nsink", input = "i\\\\nin")
                public class Backslashed {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.Backslashed", "t.BackslashedStreamOperative", code)))
                .contains("p\\nid", "s\\nsink", "i\\nin");
    }

    @Test
    void keepsAQuoteAndABackslashInStaticEntityKeysExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.EntitiesFor;
                import com.flezha.sdk.authoring.Procedure;
                import java.util.List;
                @Procedure(id = "odd_keys", keys = {"k\\"1", "k\\\\n2"})
                public class OddKeys {
                    @EntitiesFor public List<String> route(String in) { return List.of(); }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.OddKeys", "t.OddKeysProcedure", code)))
                .contains("k\"1", "k\\n2");
    }

    @ParameterizedTest(name = "{0} names the procedure after the written id")
    @ValueSource(
            strings = {
                "@Procedure(id = \"sugar_store_input\")",
                "@StreamOperative(id = \"sugar_store_input\", sink = \"sugar_store_sink\")",
                "@WorkflowOperative(id = \"sugar_store_input\")"
            })
    void namesTheProcedureAfterTheWrittenId(String annotation) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                %s
                public class SugarStore {
                    public String run(String in) { return in; }
                }
                """
                        .formatted(annotation);
        assertThat(generated("t.SugarStore", "t.SugarStoreProcedure", code))
                .contains("public String name() { return \"sugar_store_input\"; }");
    }

    /** The value of every string literal in a generated source, which must parse. */
    private static List<String> stringLiterals(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, diags, List.of("-proc:none"), null, List.of(new StringSource("g.Generated", source)));
        List<String> literals = new ArrayList<>();
        try {
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitLiteral(LiteralTree literal, Void unused) {
                        if (literal.getValue() instanceof String s) {
                            literals.add(s);
                        }
                        return null;
                    }
                }.scan(unit, null);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(diags.getDiagnostics())
                .as("generated source must parse:%n%s", source)
                .isEmpty();
        return literals;
    }

    /** Runs the processor alone over {@code code}, writing generated sources under {@code gen}; whether it succeeded. */
    private boolean process(String fqn, String code, Path gen, DiagnosticCollector<JavaFileObject> diags) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8);
        try {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            fm.setLocation(
                    StandardLocation.SOURCE_OUTPUT,
                    List.of(Files.createDirectories(gen).toFile()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JavaCompiler.CompilationTask task =
                compiler.getTask(null, fm, diags, List.of("-proc:only"), null, List.of(new StringSource(fqn, code)));
        task.setProcessors(List.of(new FlezhaProcessor()));
        return task.call();
    }

    /** Runs the processor over a well-formed source; the directory its generated sources are under. */
    private Path generate(String fqn, String code) {
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Path gen = out.resolve("gen-" + fqn);
        assertThat(process(fqn, code, gen, diags))
                .as("processing a well-formed @Procedure should succeed: %s", diags.getDiagnostics())
                .isTrue();
        return gen;
    }

    /** Runs the processor over a well-formed source and returns one generated source's text. */
    private String generated(String fqn, String generatedFqn, String code) {
        try {
            return Files.readString(generate(fqn, code).resolve(generatedFqn.replace('.', '/') + ".java"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Every source the processor wrote for {@code fqn}, as paths relative to the output root. */
    private List<String> generatedFiles(String fqn, String code) {
        Path gen = generate(fqn, code);
        try (var walk = Files.walk(gen)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> gen.relativize(path).toString())
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> diagnose(String fqn, String code) {
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        assertThat(process(fqn, code, out.resolve("gen"), diags))
                .as("compilation should fail on a malformed annotation")
                .isFalse();
        return diags.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .toList();
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String fqn, String code) {
            super(URI.create("string:///" + fqn.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
