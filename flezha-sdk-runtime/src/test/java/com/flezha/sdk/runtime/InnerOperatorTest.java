package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.LocalOperator;
import com.flezha.sdk.SinkOutput;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.Embedding;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.operator.OperatorClassRegistry;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * An operator inside an operator, run locally: a flow class whose keyed edge ends in a steps class
 * runs one stepped instance of it per key, each with its own state, and the instance's exit output
 * continues on the outer graph; a flow class is inlined under prefixed names.
 */
class InnerOperatorTest {

    @Test
    void everyKeyRunsItsOwnScopeAndTheExitContinuesTheOuterGraph() {
        assertThat(released(Gateway.class, List.of("alice:hi", "bob:yo", "alice:again")))
                .containsExactlyInAnyOrder("alice#1:hi", "alice#2:again", "bob#1:yo");
    }

    @Test
    void aThirdKeyPastMaxCellsIsDropped() {
        assertThat(released(Gateway.class, List.of("alice:hi", "bob:yo", "carol:hey")))
                .containsExactlyInAnyOrder("alice#1:hi", "bob#1:yo");
    }

    @Test
    void aValueATaskletEmitsEntersTheScopeInItsEntryCodec() {
        assertThat(released(AfterATasklet.class, List.of("  alice:hi  "))).containsExactly("alice#1:hi");
    }

    @Test
    void aFlowClassIsInlinedUnderPrefixedNames() {
        assertThat(released(Inlining.class, List.of("  hi  "))).containsExactly("HI");
        assertThat(OperatorClassRegistry.getTopology("inner_inlining_java")
                        .taskletNameToId()
                        .keySet())
                .containsExactlyInAnyOrder("inner_cleanup_java.trim", "inner_cleanup_java.upper", "reply");
    }

    @Test
    void anInlinedClassWithTwoEntriesAndNoInputIsRefusedNamingBoth() {
        assertThatThrownBy(() -> LocalOperator.start(AmbiguousEntry.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("topology.embedded_entry_ambiguous");
                    assertThat(e.getMessage())
                            .contains("inner_two_entries_java")
                            .contains("a, b");
                });
    }

    @Test
    void aStepsClassEmbeddingAStepsClassIsRefusedNamingBoth() {
        assertThatThrownBy(() -> LocalOperator.start(StepsOuter.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("topology.nested_steps");
                    assertThat(e.getMessage())
                            .contains("inner_steps_outer_java")
                            .contains("inner_session_java");
                });
    }

    @Test
    void aSinkRightAfterAnEmbeddedClassIsRefused() {
        assertThatThrownBy(() -> LocalOperator.start(SinkAfter.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("topology.invalid_setting");
                    assertThat(e.getMessage()).contains("out");
                });
    }

    private static List<String> released(Class<?> operator, List<String> inputs) {
        ConcurrentLinkedQueue<SinkOutput> released = new ConcurrentLinkedQueue<>();
        try (LocalOperator run = LocalOperator.start(operator)) {
            run.onSink("out", released::add);
            inputs.forEach(input -> run.feed("requests", input));
            run.runUntilIdle(Duration.ofSeconds(5));
        }
        return released.stream()
                .map(output -> new String(output.payload(), StandardCharsets.UTF_8))
                .toList();
    }

    public static final class Turns {
        public long turns;
    }

    public record OneMore() implements Change<Turns> {
        @Override
        public void applyTo(Turns state) {
            state.turns += 1;
        }
    }

    /** One turn of a session: {@code key:text} answered as {@code key#turns:text}. */
    @Tasklet.Spec(name = "turn")
    public static final class Turn implements Tasklet<String, Turns, String> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Turns.class, OneMore.class);
        }

        @Override
        public void run(String input, Ctx<Turns, String> ctx) throws TaskletException {
            String[] parts = input.split(":", 2);
            ctx.change(new OneMore());
            ctx.emit(parts[0] + "#" + ctx.state().turns + ":" + parts[1]);
        }
    }

    @Tasklet.Spec(name = "reply")
    public static final class Reply implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "trim")
    public static final class Trim implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.strip());
        }
    }

    @Tasklet.Spec(name = "upper")
    public static final class Upper implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.toUpperCase());
        }
    }

    public static final class Session implements Operator {
        @Override
        public String factoryId() {
            return "inner_session_java";
        }

        @Override
        public Run run() {
            return Run.steps(Each.PER_INPUT);
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "turns").into(Turn.class).named("answer");
        }
    }

    public static final class Gateway implements Operator {
        @Override
        public String factoryId() {
            return "inner_gateway_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests")
                    .keyBy(line -> line.split(":", 2)[0])
                    .into(Session.class, String.class, Embedding.of().maxCells(2))
                    .into(Reply.class)
                    .sink("out");
        }
    }

    public static final class AfterATasklet implements Operator {
        @Override
        public String factoryId() {
            return "inner_after_a_tasklet_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests")
                    .into(Trim.class)
                    .keyBy(line -> line.split(":", 2)[0])
                    .into(Session.class, String.class)
                    .into(Reply.class)
                    .sink("out");
        }
    }

    public static final class Cleanup implements Operator {
        @Override
        public String factoryId() {
            return "inner_cleanup_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "raw").into(Trim.class).into(Upper.class);
        }
    }

    public static final class Inlining implements Operator {
        @Override
        public String factoryId() {
            return "inner_inlining_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests")
                    .into(Cleanup.class, String.class)
                    .into(Reply.class)
                    .sink("out");
        }
    }

    public static final class TwoEntries implements Operator {
        @Override
        public String factoryId() {
            return "inner_two_entries_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "a").into(Trim.class);
            c.input(String.class, "b").into(Upper.class);
        }
    }

    public static final class AmbiguousEntry implements Operator {
        @Override
        public String factoryId() {
            return "inner_ambiguous_entry_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(TwoEntries.class, String.class).into(Reply.class);
        }
    }

    public static final class StepsOuter implements Operator {
        @Override
        public String factoryId() {
            return "inner_steps_outer_java";
        }

        @Override
        public Run run() {
            return Run.steps(Each.PER_INPUT);
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Session.class, String.class).into(Reply.class);
        }
    }

    public static final class SinkAfter implements Operator {
        @Override
        public String factoryId() {
            return "inner_sink_after_java";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests").into(Session.class, String.class).sink("out");
        }
    }
}
