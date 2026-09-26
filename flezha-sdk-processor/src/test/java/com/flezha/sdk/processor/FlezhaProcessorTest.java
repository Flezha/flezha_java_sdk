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
    void rejectsCellKeyAndNamesItsReplacement() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                import java.util.Optional;
                @Tasklet(id = "bad")
                public class Bad {
                    @CellKey public Optional<String> s(String in) { return Optional.empty(); }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Bad", code)).anyMatch(m -> m.contains(".keyBy(f)"));
    }

    @Test
    void rejectsInitStateOnStatelessTasklet() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.InitState;
                import com.flezha.sdk.authoring.Tasklet;
                import java.util.Optional;
                @Tasklet(id = "bad_init")
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
                import com.flezha.sdk.authoring.Tasklet;
                @Tasklet(id = "keyed")
                public class Plain {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Plain", "t.PlainTasklet", code)).contains("ctx.emit(out);");

        String keyed =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                @Tasklet(id = "keyed", key = "k")
                public class Keyed {
                    public static String k(String out) { return out; }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Keyed", "t.KeyedTasklet", keyed)).contains("ctx.emitKeyed(t.Keyed.k(out), out);");
    }

    /**
     * The generator used to write {@code .named(x).sink(x)} because a sink
     * named a plugin rather than a channel, so sugared and hand-written code
     * shipped different wire names for the same operator.
     */
    @Test
    void theGeneratedChannelsNamesTheChannelWithTheSinkAndNothingElse() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "sugar", sink = "items_output")
                public class Sugared {
                    public String run(String in) { return in; }
                }
                """;
        String source = generated("t.Sugared", "t.SugaredUnboundedOperator", code);

        assertThat(source).contains(".sink(\"items_output\")");
        assertThat(source).doesNotContain(".named(");
    }

    @Test
    void contextParameterRunEmitsThroughTheBody() {
        String stateless =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                import com.flezha.sdk.tasklet.TaskletCtx;
                @Tasklet(id = "fanout", emits = String.class)
                public class Fanout {
                    public void run(String in, TaskletCtx ctx) { ctx.emit(in); ctx.emit(in); }
                }
                """;
        String generated = generated("t.Fanout", "t.FanoutTasklet", stateless);
        // The body owns every emit: the wrapper passes the context and adds none.
        assertThat(generated).contains("impl.run(input, ctx);");
        assertThat(generated).doesNotContain("ctx.emit(out)");
        // `emits` lands as the Output type argument, which is where the runtime
        // reads it from — the tokens are not restated.
        assertThat(generated)
                .contains("implements Tasklet<java.lang.String, com.flezha.sdk.state.Stateless,"
                        + " java.lang.String, com.flezha.sdk.state.NoChange, WallClock, Void>");
        assertThat(generated).doesNotContain("outputType()");

        String stateful =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                import com.flezha.sdk.tasklet.TaskletCtx;
                import com.flezha.sdk.state.NoChange;
                import com.flezha.sdk.state.StateHandle;
                @Tasklet(id = "fanout2", emits = String.class)
                public class Fanout2 {
                    public void run(String in, StateHandle<String, NoChange> state, TaskletCtx ctx) {
                        ctx.emit(in);
                    }
                }
                """;
        assertThat(generated("t.Fanout2", "t.Fanout2Tasklet", stateful)).contains("impl.run(input, state, ctx);");
    }

    @Test
    void aWrittenCompensateIsPassedTheStateRunTakes() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.State;
                import com.flezha.sdk.authoring.Tasklet;
                import com.flezha.sdk.tasklet.TaskletCtx;
                import java.util.List;
                @Tasklet(id = "reserve", emits = String.class)
                public class Reserve {
                    @State
                    public static class Reservation { public String id = ""; }
                    public void run(String in, ReservationHandle reservation, TaskletCtx ctx) {
                        reservation.setId(in);
                        ctx.emit(in);
                    }
                    public void compensate(List<String> done, ReservationHandle reservation, TaskletCtx ctx) {}
                }
                """;
        String tasklet = generated("t.Reserve", "t.ReserveTasklet", code);
        assertThat(tasklet)
                .contains("@Override public void compensate(List<java.lang.String> completedOutput,"
                        + " StateHandle<t.Reserve.Reservation, t.ReservationChange> state, TaskletCtx ctx)")
                .contains("impl.compensate(completedOutput, new t.ReservationHandle(state), ctx);");

        String stateless =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                import java.util.List;
                @Tasklet(id = "notify")
                public class Notify {
                    public String run(String in) { return in; }
                    public void compensate(List<String> done) {}
                }
                """;
        assertThat(generated("t.Notify", "t.NotifyTasklet", stateless)).contains("impl.compensate(completedOutput);");
    }

    // Writing the hook is what declares it, so a tasklet without one must not
    // gain an override that would register it as compensable.
    @Test
    void aTaskletWithoutCompensateGeneratesNone() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                @Tasklet(id = "plain")
                public class Plain {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Plain", "t.PlainTasklet", code)).doesNotContain("compensate");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compensateShapes")
    void aCompensateOfTheWrongShapeIsRefused(String why, String methods) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                import com.flezha.sdk.state.NoChange;
                import com.flezha.sdk.state.StateHandle;
                import java.util.List;
                @Tasklet(id = "undo")
                public class Undo {
                    public String run(String in, StateHandle<String, NoChange> state) { return in; }
                    %s
                }
                """
                        .formatted(methods);
        assertThat(diagnose("t.Undo", code))
                .anySatisfy(m -> assertThat(m).startsWith("@Tasklet").contains("`compensate`"));
    }

    static Stream<Arguments> compensateShapes() {
        return Stream.of(
                Arguments.of(
                        "a list of another type",
                        "public void compensate(List<Integer> done, StateHandle<String, NoChange> state) {}"),
                Arguments.of("no state parameter", "public void compensate(List<String> done) {}"),
                Arguments.of(
                        "a return value",
                        "public String compensate(List<String> done, StateHandle<String, NoChange> state) {"
                                + " return \"\"; }"),
                Arguments.of(
                        "two of them",
                        "public void compensate(List<String> done, StateHandle<String, NoChange> state) {}\n"
                                + "public void compensate(List<String> done, StateHandle<String, NoChange> s,"
                                + " com.flezha.sdk.tasklet.TaskletCtx ctx) {}"));
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
        assertThat(change).contains(".addLast(c.value())");
        assertThat(change).contains("pollFirst()");
    }

    // A nested state's handle and change are top-level classes of its package,
    // so the tasklet cannot find the state by dropping `Handle` from the handle's
    // name (KB-475).
    @Test
    void aStateNestedInItsTaskletIsNamedByItsOwnName() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.State;
                import com.flezha.sdk.authoring.Tasklet;
                import java.util.ArrayDeque;
                import java.util.Deque;
                @Tasklet(id = "track")
                public class Track {
                    @State
                    public static class Window {
                        public Deque<Double> prices = new ArrayDeque<>();
                    }
                    public Double run(Double price, WindowHandle window) {
                        window.pushBackPrices(price);
                        return price;
                    }
                }
                """;
        String tasklet = generated("t.Track", "t.TrackTasklet", code);
        assertThat(tasklet).contains("Tasklet<java.lang.Double, t.Track.Window, java.lang.Double, t.WindowChange,");
        assertThat(tasklet).contains("return new t.Track.Window();");
    }

    // Kryo builds a registered type from a record's canonical constructor or
    // from a no-arg one, and the generated variants have neither until they are
    // records: every read of a journaled derived change failed on the shape
    // alone (KB-467).
    @Test
    void aDerivedChangeIsASealedInterfaceOfRecords() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Journal;
                import com.flezha.sdk.authoring.State;
                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                @State
                public class TallyState {
                    public long n;
                    public List<String> seen = new ArrayList<>();
                    @Journal(Journal.Mode.MAP_SET)
                    public Map<String, Double> last = new HashMap<>();
                }
                """;
        String change = generated("t.TallyState", "t.TallyStateChange", code);
        assertThat(change).contains("public sealed interface TallyStateChange extends Change {");
        assertThat(change).contains("record SetN(long value) implements TallyStateChange {}");
        assertThat(change).contains("record PushSeen(java.lang.String value) implements TallyStateChange {}");
        // The components are spelled as a hand-written Change spells them
        // (`RollingWindowTwin.WindowChange` in the test app), so a derived
        // variant and its twin read the same at every call site.
        assertThat(change)
                .contains(
                        "record SetLast(java.lang.String key, java.lang.Double value) implements TallyStateChange {}");
        assertThat(change).contains("state.last.put(c.key(), c.value());");
        // The record brings its own equality and its own construction; neither
        // is hand-written any more, and there is no class to construct.
        assertThat(change).doesNotContain("abstract class");
        assertThat(change).doesNotContain("hashCode");
    }

    @Test
    void theGeneratedEntryChannelKeepsItsDefaultName() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "default_entry", sink = "echo")
                public class DefaultEntry {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.DefaultEntry", "t.DefaultEntryUnboundedOperator", code))
                .contains("c.input(java.lang.String.class, \"input\")");
    }

    @Test
    void theAnnotationNamesTheGeneratedEntryChannel() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "named_entry", sink = "echo", input = "prices")
                public class NamedEntry {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.NamedEntry", "t.NamedEntryUnboundedOperator", code))
                .contains("c.input(java.lang.String.class, \"prices\")");
    }

    @Test
    void aShortFormWithNoSinkDeclaresNoPluginChannel() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "counter", input = "ticks")
                public class Counter {
                    public void run(String in) {}
                }
                """;
        String source = generated("t.Counter", "t.CounterUnboundedOperator", code);
        assertThat(source).contains("c.input(java.lang.String.class, \"ticks\").into(CounterTasklet.class);");
        assertThat(source).doesNotContain(".sink(");
    }

    @ParameterizedTest(name = "{0} with no id is refused")
    @ValueSource(strings = {"@UnboundedOperator(sink = \"out\")", "@BoundedOperator(id = \"\", sink = \"out\")"})
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
    void anAnnotatedClassImplementingAnOperatorInterfaceIsRefused() {
        String code =
                """
                package t;
                import com.flezha.sdk.channel.ChannelBuilder;
                @com.flezha.sdk.authoring.UnboundedOperator(id = "both", sink = "out")
                public class Both implements com.flezha.sdk.operator.UnboundedOperator {
                    @Override public String factoryId() { return "both"; }
                    @Override public void channels(ChannelBuilder c) {}
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Both", code))
                .anyMatch(m -> m.startsWith("ShortFormWithTopology") && m.contains("implements UnboundedOperator"));
    }

    @Test
    void aSinkOnAVoidRunIsRefused() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "drain", sink = "out")
                public class Drain {
                    public void run(String in) {}
                }
                """;
        assertThat(diagnose("t.Drain", code)).anyMatch(m -> m.startsWith("SinkWithoutOutput"));
    }

    @ParameterizedTest(name = "`{0}` on @Tasklet is refused")
    @ValueSource(strings = {"sink = \"echo\"", "input = \"prices\"", "kind = \"stream\"", "onFull = \"drop_oldest\""})
    void anOperatorMemberOnATaskletIsRefused(String member) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                @Tasklet(id = "do_nothing", %s)
                public class DoNothing {
                    public String run(String in) { return in; }
                }
                """
                        .formatted(member);
        String name = member.substring(0, member.indexOf(' '));
        assertThat(diagnose("t.DoNothing", code))
                .anyMatch(m -> m.contains("`" + name + "`")
                        && m.contains("@UnboundedOperator")
                        && m.contains("@BoundedOperator"));
    }

    /** fails when: a short form's entry-channel or tasklet setting stops reaching what it describes. */
    @ParameterizedTest(name = "{0} carries capacity and onFull")
    @ValueSource(strings = {"UnboundedOperator", "BoundedOperator"})
    void aShortFormCarriesItsEntryAndTaskletSettings(String kind) {
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
                .contains(".onFull(com.flezha.sdk.channel.OverflowPolicy.DROP_OLDEST).into(CappedTasklet.class)");
        assertThat(generated("t.Capped", "t.CappedTasklet", code)).contains("mailboxCapacity() { return 4; }");
    }

    /**
     * fails when: a refusal of the {@code run} shape or of a tasklet member names an annotation
     * other than the one the author wrote. {@code %s} in the expected text is that annotation.
     */
    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("refusalsNamingTheAnnotation")
    void aRefusalNamesTheAnnotationTheAuthorWrote(String annotation, String members, String body, String message) {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                import com.flezha.sdk.tasklet.TaskletCtx;
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
        for (String annotation : List.of("@Tasklet", "@UnboundedOperator", "@BoundedOperator")) {
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
                    "public String run(String in, TaskletCtx ctx) { return in; }",
                    "%s `run` with a `TaskletCtx` parameter makes the body responsible for every emit"));
            rows.add(Arguments.of(
                    annotation,
                    ", key = \"k\", emits = String.class",
                    keyOf + "public void run(String in, TaskletCtx ctx) { ctx.emit(in); }",
                    "%s(key = ...) conflicts with a `TaskletCtx` parameter"));
            rows.add(Arguments.of(
                    annotation,
                    ", emits = String.class",
                    echo,
                    "%s(emits = ...) declares the output type of a `TaskletCtx` `run`"));
            rows.add(Arguments.of(
                    annotation,
                    ", key = \"k\"",
                    keyOf + "public void run(String in) { }",
                    "%s(key = ...) requires a non-void return type; there is no emit to key"));
            rows.add(Arguments.of(
                    annotation,
                    "",
                    "@CellsFor public List<String> route(String in) { return List.of(); } " + echo,
                    "static cell requires %s(keys = {...}) alongside the @CellsFor method"));
            rows.add(
                    Arguments.of(annotation, ", keys = {\"a\"}", echo, "%s(keys = {...}) requires a @CellsFor method"));
            if (!annotation.equals("@Tasklet")) {
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
                @Tasklet(id = "twice")
                @UnboundedOperator(id = "twice", sink = "out")
                public class Twice {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(diagnose("t.Twice", code)).anyMatch(m -> m.contains("@Tasklet") && m.contains("@UnboundedOperator"));
    }

    @Test
    void aBoundedShortFormGeneratesABoundedOperator() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @BoundedOperator(id = "place", sink = "placed", input = "order")
                public class Place {
                    public String run(String in) { return in; }
                }
                """;
        String source = generated("t.Place", "t.PlaceBoundedOperator", code);
        assertThat(source).contains("implements BoundedOperator");
        assertThat(source).contains("public void channels(ChannelBuilder c)");
        assertThat(source)
                .contains("c.input(java.lang.String.class, \"order\").into(PlaceTasklet.class).sink(\"placed\");");
        assertThat(source)
                .as("an unwritten loop mode is the interface's default, not a restatement of it")
                .doesNotContain("loopMode()");
    }

    @Test
    void aShortFormBoundedDeclaresItsLoopMode() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                import com.flezha.sdk.operator.LoopMode;
                @BoundedOperator(id = "chat", sink = "replies", loopMode = LoopMode.PER_INPUT)
                public class Chat {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generated("t.Chat", "t.ChatBoundedOperator", code))
                .contains("public com.flezha.sdk.operator.LoopMode loopMode() {"
                        + " return com.flezha.sdk.operator.LoopMode.PER_INPUT; }");
    }

    @Test
    void writesNoAggregateRegistrationClass() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "alone", sink = "out")
                public class Alone {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generatedFiles("t.Alone", code))
                .containsExactlyInAnyOrder("t/AloneTasklet.java", "t/AloneUnboundedOperator.java");
    }

    @Test
    void aTaskletGeneratesNoOperator() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.Tasklet;
                @Tasklet(id = "single_mode")
                public class SingleMode {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(generatedFiles("t.SingleMode", code)).containsExactly("t/SingleModeTasklet.java");
    }

    @Test
    void keepsAQuoteInTheShortFormStringsExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "p\\"id", sink = "s\\"sink", input = "i\\"in")
                public class Quoted {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.Quoted", "t.QuotedUnboundedOperator", code)))
                .contains("p\"id", "s\"sink", "i\"in");
    }

    @Test
    void keepsABackslashInTheShortFormStringsExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.*;
                @UnboundedOperator(id = "p\\\\nid", sink = "s\\\\nsink", input = "i\\\\nin")
                public class Backslashed {
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.Backslashed", "t.BackslashedUnboundedOperator", code)))
                .contains("p\\nid", "s\\nsink", "i\\nin");
    }

    @Test
    void keepsAQuoteAndABackslashInStaticCellKeysExactly() {
        String code =
                """
                package t;
                import com.flezha.sdk.authoring.CellsFor;
                import com.flezha.sdk.authoring.Tasklet;
                import java.util.List;
                @Tasklet(id = "odd_keys", keys = {"k\\"1", "k\\\\n2"})
                public class OddKeys {
                    @CellsFor public List<String> route(String in) { return List.of(); }
                    public String run(String in) { return in; }
                }
                """;
        assertThat(stringLiterals(generated("t.OddKeys", "t.OddKeysTasklet", code)))
                .contains("k\"1", "k\\n2");
    }

    @ParameterizedTest(name = "{0} names the tasklet after the written id")
    @ValueSource(
            strings = {
                "@Tasklet(id = \"sugar_store_input\")",
                "@UnboundedOperator(id = \"sugar_store_input\", sink = \"sugar_store_sink\")",
                "@BoundedOperator(id = \"sugar_store_input\")"
            })
    void namesTheTaskletAfterTheWrittenId(String annotation) {
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
        assertThat(generated("t.SugarStore", "t.SugarStoreTasklet", code))
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
                .as("processing a well-formed @Tasklet should succeed: %s", diags.getDiagnostics())
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
