package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Steps relay activations: a tasklet's completion request, the one edge output a
 * stage releases, the transform each consumer applies at delivery, and
 * compensation.
 */
class StepsDispatchTest extends DispatchTestBase {

    @Test
    @DisplayName("a steps tasklet's complete rides its effect, the later call winning")
    void aStepsTaskletsCompleteRidesItsEffect() {
        RelayClassRegistry.registerClass(CompletingSteps.class);
        ItemEffect effect = soleEffect(work("completing_steps", 0, Optional.empty(), 0, input(0, "bob", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.complete())
                .hasValueSatisfying(result ->
                        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("done:bob"));
    }

    @Test
    @DisplayName("A durable steps relay invocation ships the same record: one Change and one keyed output")
    void aDurableStepsInvocationShipsTheSameRecord() {
        RelayClassRegistry.registerClass(DurableCountSteps.class);
        ItemEffect effect = soleEffect(work("durable_count_steps", 0, Optional.empty(), 0, input(0, "key:a", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.changes()).hasSize(1);
        assertThat(effect.outputs()).hasSize(1);
        assertThat(effect.outputs().get(0).key()).contains("k");
    }

    @Test
    @DisplayName("A Java steps tasklet's emit rides its cell's record with its key and metadata")
    void aStepsTaskletsKeyedEmitRidesItsEffect() {
        RelayClassRegistry.registerClass(KeyedMetaSteps.class);
        ItemEffect effect = soleEffect(work("keyed_meta_steps", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.outputs()).hasSize(1);
        Output out = effect.outputs().get(0);
        assertThat(out.key()).contains("k-x");
        assertThat(out.metadata()).contains(Map.entry("user", "meta"));
    }

    @Test
    @DisplayName("an item that fails after completing carries no completion")
    void aFailedItemCarriesNoCompletion() {
        RelayClassRegistry.registerClass(CompletingSteps.class);
        ItemEffect effect = soleEffect(work("completing_steps", 0, Optional.empty(), 0, input(0, "fail", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(effect.complete()).isEmpty();
    }

    @Test
    @DisplayName("a steps stage releases ONE untransformed edge output, whatever the consumer count")
    void stepsStageReleasesOneEdgeOutputPerValue() {
        RelayClassRegistry.registerClass(FanOutSteps.class);
        ItemEffect effect = soleEffect(work("fanout_steps", 0, Optional.empty(), 0, input(0, "v", 1)));

        // The steps relay fold discards the consumer and the driver fans the
        // released batch out itself, so `steps_fan`'s two consumers must not
        // double it.
        assertThat(effect.outputs()).hasSize(1);
        Output released = effect.outputs().get(0);
        assertThat(released.target()).isInstanceOf(OutputTarget.Edge.class);
        assertThat(((OutputTarget.Edge) released.target()).channel()).isEqualTo(1);
        assertThat(released.key()).isEmpty();
        assertThat(released.eventTime()).isEmpty();
        assertThat(new KryoCodec(List.of(), false).deserialize(released.payload(), Object.class))
                .as("the released value is raw: the consumer edge's map belongs to the consumer")
                .isEqualTo("mid:v");
    }

    @Test
    @DisplayName("a steps terminal nothing consumes hands its value to the core as the pass's carry")
    void stepsTerminalHandsItsCarry() {
        RelayClassRegistry.registerClass(CarrySteps.class);
        ItemEffect effect = soleEffect(work("carry_steps", 0, Optional.empty(), 0, input(0, "3", 1)));

        assertThat(effect.outputs()).hasSize(1);
        Output carried = effect.outputs().get(0);
        assertThat(carried.target()).isEqualTo(new OutputTarget.Edge(1, 0));
        assertThat(new KryoCodec(List.of(), false).deserialize(carried.payload(), Object.class))
                .isEqualTo("3");
    }

    /** A chained loop whose terminal channel nothing consumes: its value is the carry. */
    public static final class CarrySteps implements Relay {
        @Override
        public String factoryId() {
            return "carry_steps";
        }

        @Override
        public Run run() {
            return Run.steps(Each.CHAINED);
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(LeafEmitTask.class).named("carry");
        }
    }

    @Test
    @DisplayName("each steps consumer applies its own edge transform exactly once, at delivery")
    void stepsConsumerAppliesItsEdgeOnce() {
        RelayClassRegistry.registerClass(FanOutSteps.class);
        ItemEffect stage = soleEffect(work("fanout_steps", 0, Optional.empty(), 0, input(0, "v", 1)));
        byte[] released = stage.outputs().get(0).payload();

        // The one released value reaches BOTH children — the driver's fan-out —
        // and each evaluates its own edge in valuesForCell.
        soleEffect(work(
                "fanout_steps",
                1,
                Optional.empty(),
                0,
                new WorkItem.Input(1, released, List.of(), OptionalLong.empty(), false, 2)));
        soleEffect(work(
                "fanout_steps",
                2,
                Optional.empty(),
                0,
                new WorkItem.Input(1, released, List.of(), OptionalLong.empty(), false, 3)));

        assertThat(RECORDED)
                .as("the mapped edge applies once (never producer-side AND again here)")
                .containsExactly("=mapped:mid:v", "=mid:v");
    }

    @Test
    @DisplayName("A handoff is journaled with nothing declared")
    void aHandoffIsJournaledWithNothingDeclared() {
        // Durable, and the chain says nothing about how the value between two
        // tasklets is encoded: the core derives the journal codec from the
        // channel's kind, and this SDK hands it bytes either way.
        RelayClassRegistry.registerClass(DurableHandoffSteps.class);
        ItemEffect effect = soleEffect(work("durable_handoff_steps", 0, Optional.empty(), 0, input(0, "v", 1)));

        assertThat(effect.outputs()).hasSize(1);
        Output released = effect.outputs().get(0);
        assertThat(released.target()).isInstanceOf(OutputTarget.Edge.class);
        assertThat(new KryoCodec(List.of(), false).deserialize(released.payload(), Object.class))
                .as("the handoff carried into the task-commit barrier")
                .isEqualTo("mid:v");
    }

    @Test
    @DisplayName("A timer inside a steps tasklet fires: its registration reaches the effect, as in a stream")
    void aTimerInsideABoundedTaskletFires() {
        RelayClassRegistry.registerClass(TimerSteps.class);
        ItemEffect effect = soleEffect(work("timer_steps", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.timers())
                .as("the registration is the core's to commit and arm")
                .containsExactly(TimerOp.register(TimerKind.PROCESSING_TIME, 1_001));
    }

    @Test
    @DisplayName("The retired wake method does not compile: Relay declares no wakeAfter")
    void theRetiredWakeMethodDoesNotCompile() {
        assertThat(java.util.Arrays.stream(com.flezha.sdk.relay.Relay.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .doesNotContain("wakeAfter");
    }

    /** A durable steps relay whose one tasklet applies a Change and emits keyed. */
    public static final class DurableCountSteps implements com.flezha.sdk.relay.Relay {
        @Override
        public String factoryId() {
            return "durable_count_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed").into(CountTask.class).keyed().output(v -> v.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A durable steps relay whose one tasklet emits with a key and metadata. */
    public static final class KeyedMetaSteps implements com.flezha.sdk.relay.Relay {
        @Override
        public String factoryId() {
            return "keyed_meta_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed").into(KeyedTask.class).keyed().output(v -> v.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A steps relay whose one tasklet registers a processing-time timer. */
    public static final class TimerSteps implements com.flezha.sdk.relay.Relay {
        @Override
        public String factoryId() {
            return "timer_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed").into(TimerAfterTask.class);
        }
    }

    @Tasklet.Spec(name = "timer_after_task")
    public static final class TimerAfterTask extends Task<Void> {
        public TimerAfterTask() {
            super((input, ctx) -> ctx.timerAfter(Duration.ofSeconds(1)));
        }
    }

    /** A DURABLE structured chain that declares nothing about its handoff. */
    public static final class DurableHandoffSteps implements com.flezha.sdk.relay.Relay {
        @Override
        public String factoryId() {
            return "durable_handoff_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed")
                    .into(MidTask.class)
                    .named("steps_durable")
                    .into(KeyRecorder.class);
        }
    }

    @Test
    @DisplayName("CompensateRun decodes the recorded outputs and collects the undo emits")
    void compensateRunUndoesWithDecodedOutputs() {
        RelayClassRegistry.registerClass(SagaSteps.class);
        ItemEffect forward = soleEffect(work("saga_steps", 0, Optional.empty(), 0, input(0, "job", 1)));
        assertThat(forward.outputs()).hasSize(1);

        ActivationOutcome out = outcome(command(
                "saga_steps",
                0,
                Optional.empty(),
                new ActivationKind.CompensateRun(
                        List.of(forward.outputs().get(0).payload()))));
        List<ItemEffect> effects = ((ActivationOutcome.Work) out).effects();
        assertThat(effects).hasSize(1);
        assertThat(effects.get(0).verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effects.get(0).outputs())
                .as("the undo emit rides the effect")
                .hasSize(1);
        assertThat(effects.get(0).complete()).isEmpty();
        assertThat(RECORDED).containsExactly("compensated:fwd:job");
    }
}
