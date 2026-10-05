package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.relay.TaskletDescriptor;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one tasklet form, dispatched: state and changes through the context, emits, cells and the clock. */
class TaskletFormDispatchTest extends DispatchTestBase {

    @Test
    @DisplayName("One run emits any number of values through the context; A Java tasklet that emits nothing")
    void oneRunEmitsAnyNumberOfValuesThroughTheContext() {
        RelayClassRegistry.registerClass(EmitManyRelay.class);

        ItemEffect two = soleEffect(work("emit_many", 0, Optional.empty(), 0, input(0, "two", 1)));
        ItemEffect none = soleEffect(work("emit_many", 0, Optional.empty(), 1, input(0, "none", 2)));

        assertThat(two.outputs())
                .extracting(o -> new String(o.payload(), StandardCharsets.UTF_8))
                .containsExactly("first", "second");
        assertThat(none.outputs()).isEmpty();
    }

    @Test
    @DisplayName("A Void state keeps nothing, and a Void output declares no output channel")
    void aVoidStateKeepsNothing() {
        RelayClassRegistry.registerClass(StaticRelay.class);
        ItemEffect effect = soleEffect(work("static_op", 0, Optional.of("a"), 0, input(0, "a:1", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.changes()).isEmpty();
        TaskletDescriptor desc = RelayClassRegistry.getTopology("static_op").tasklet(0);
        assertThat(desc.model().stateless()).isTrue();
        assertThat(desc.outputChannelId()).isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("a Void-state tasklet that records a change fails its input, recording nothing")
    void aVoidStateChangeIsRefused() {
        RelayClassRegistry.registerClass(VoidChangeRelay.class);
        ItemEffect effect = soleEffect(work("void_change", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(effect.changes()).isEmpty();
    }

    @Test
    @DisplayName("A change is applied and recorded")
    void aChangeIsAppliedAndRecorded() {
        RelayClassRegistry.registerClass(CounterRelay.class);
        ItemEffect effect = soleEffect(work("counter_op", 0, Optional.empty(), 0, input(0, "a", 1)));

        assertThat(effect.changes()).hasSize(1);
        assertThat(stateJson("counter_op", Optional.empty())).isEqualTo("{\"n\":1}");
    }

    @Test
    @DisplayName("A restart folds the log through applyTo, in seq order, back to the state before it")
    void aRestartFoldsTheLogThroughApplyTo() {
        RelayClassRegistry.registerClass(CounterRelay.class);
        List<byte[]> log = new java.util.ArrayList<>();
        for (int seq = 0; seq < 3; seq++) {
            log.addAll(soleEffect(work("counter_op", 0, Optional.empty(), seq, input(0, "a", seq)))
                    .changes());
        }
        String before = stateJson("counter_op", Optional.empty());

        dispatcher = new ActivationDispatcher();
        outcome(command(
                "counter_op",
                0,
                Optional.empty(),
                new ActivationKind.RestoreFold(
                        Optional.empty(),
                        log.stream()
                                .map(c -> new ActivationKind.RestoreBody(c, OptionalInt.empty()))
                                .toList())));

        assertThat(stateJson("counter_op", Optional.empty())).isEqualTo(before).isEqualTo("{\"n\":3}");
    }

    @Test
    @DisplayName("Static-mode Tasklet creates one cell per key, each with its own instance")
    void staticModeTaskletCreatesOneCellPerKey() {
        RelayClassRegistry.registerClass(StaticRelay.class);
        TaskletDescriptor desc = RelayClassRegistry.getTopology("static_op").tasklet(0);
        assertThat(desc.cellKeys()).containsExactly("a", "b");
        @SuppressWarnings("unchecked")
        Map<String, Object> declared = ((List<Map<String, Object>>) RelayClassRegistry.getTopology("static_op")
                        .declaration()
                        .get("tasklets"))
                .get(0);
        assertThat(declared).containsEntry("cell_mode", "static").containsEntry("cell_keys", List.of("a", "b"));

        soleEffect(work("static_op", 0, Optional.of("a"), 0, input(0, "a:1", 1)));
        soleEffect(work("static_op", 0, Optional.of("b"), 0, input(0, "b:1", 1)));
        soleEffect(work("static_op", 0, Optional.of("a"), 1, input(0, "a:2", 2)));

        assertThat(RECORDED).containsExactly("a#1=a:1", "b#1=b:1", "a#2=a:2");
    }

    @Test
    @DisplayName("Logical clock re-arms on restore: ctx.now() serves the seq the core hands the invocation")
    void logicalClockReArmsOnRestore() {
        RelayClassRegistry.registerClass(LogicalRelay.class);
        TaskletDescriptor desc = RelayClassRegistry.getTopology("logical_op").tasklet(0);
        assertThat(desc.clock().wireName()).isEqualTo("logical");

        outcome(command(
                "logical_op", 0, Optional.empty(), new ActivationKind.RestoreFold(Optional.empty(), List.of())));
        soleEffect(work("logical_op", 0, Optional.empty(), 42, input(0, "x", 42)));

        assertThat(RECORDED).containsExactly("now=42");
    }

    private String stateJson(String factory, Optional<String> key) {
        ActivationOutcome json = outcome(command(factory, 0, key, new ActivationKind.LiveStateRead()));
        return new String(((ActivationOutcome.StateJson) json).json(), StandardCharsets.UTF_8);
    }

    public static final class EmitManyRelay extends Op {
        public EmitManyRelay() {
            super("emit_many", c -> c.input(String.class, "in")
                    .into(EmitManyTask.class)
                    .output("out", s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Tasklet.Spec(name = "emit_many_task")
    public static final class EmitManyTask extends Task<String> {
        public EmitManyTask() {
            super((input, ctx) -> {
                if (input.equals("two")) {
                    ctx.emit("first");
                    ctx.emit("second");
                }
            });
        }
    }

    public static final class VoidChangeRelay extends Op {
        public VoidChangeRelay() {
            super("void_change", c -> c.input(String.class, "in").into(VoidChangeTask.class));
        }
    }

    /** Declares Void state, then records a change anyway. */
    @Tasklet.Spec(name = "void_change_task")
    public static final class VoidChangeTask implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {
            ctx.change(state -> {});
        }
    }

    public static final class LogicalRelay extends Op {
        public LogicalRelay() {
            super("logical_op", c -> c.input(String.class, "in").into(LogicalTask.class));
        }
    }

    @Tasklet.Spec(name = "logical_task", logicalClock = true)
    public static final class LogicalTask implements Tasklet<String, CounterState, Void> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CountChange.class, CounterState.class);
        }

        @Override
        public void run(String input, Ctx<CounterState, Void> ctx) throws TaskletException {
            RECORDED.add("now=" + ctx.now());
            ctx.change(new CountChange(1));
        }
    }
}
