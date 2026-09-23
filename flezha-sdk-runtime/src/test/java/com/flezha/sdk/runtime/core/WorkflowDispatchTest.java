package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.runtime.workflow.WorkflowOperativeClassRegistry;
import com.flezha.sdk.state.TimerKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Workflow activations: a tasklet's completion request, the one edge output a
 * stage releases, the transform each consumer applies at delivery, and
 * compensation.
 */
class WorkflowDispatchTest extends DispatchTestBase {

    @Test
    @DisplayName("a workflow tasklet's complete rides its effect, the later call winning")
    void aWorkflowTaskletsCompleteRidesItsEffect() {
        WorkflowOperativeClassRegistry.registerClass(CompletingWorkflow.class);
        ItemEffect effect = soleEffect(work("completing_workflow", 0, Optional.empty(), 0, input(0, "bob", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.complete())
                .hasValueSatisfying(result ->
                        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("done:bob"));
    }

    @Test
    @DisplayName("an item that fails after completing carries no completion")
    void aFailedItemCarriesNoCompletion() {
        WorkflowOperativeClassRegistry.registerClass(CompletingWorkflow.class);
        ItemEffect effect = soleEffect(work("completing_workflow", 0, Optional.empty(), 0, input(0, "fail", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(effect.complete()).isEmpty();
    }

    @Test
    @DisplayName("a workflow stage releases ONE untransformed edge output, whatever the consumer count")
    void workflowStageReleasesOneEdgeOutputPerValue() {
        WorkflowOperativeClassRegistry.registerClass(FanOutWorkflow.class);
        ItemEffect effect = soleEffect(work("fanout_workflow", 0, Optional.empty(), 0, input(0, "v", 1)));

        // The workflow fold discards the consumer and the driver fans the
        // released batch out itself, so `wf_fan`'s two consumers must not
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
    @DisplayName("each workflow consumer applies its own edge transform exactly once, at delivery")
    void workflowConsumerAppliesItsEdgeOnce() {
        WorkflowOperativeClassRegistry.registerClass(FanOutWorkflow.class);
        ItemEffect stage = soleEffect(work("fanout_workflow", 0, Optional.empty(), 0, input(0, "v", 1)));
        byte[] released = stage.outputs().get(0).payload();

        // The one released value reaches BOTH children — the driver's fan-out —
        // and each evaluates its own edge in valuesForCell.
        soleEffect(work(
                "fanout_workflow",
                1,
                Optional.empty(),
                0,
                new WorkItem.Input(1, released, List.of(), OptionalLong.empty(), false, 2)));
        soleEffect(work(
                "fanout_workflow",
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
        WorkflowOperativeClassRegistry.registerClass(DurableHandoffWorkflow.class);
        ItemEffect effect = soleEffect(work("durable_handoff_workflow", 0, Optional.empty(), 0, input(0, "v", 1)));

        assertThat(effect.outputs()).hasSize(1);
        Output released = effect.outputs().get(0);
        assertThat(released.target()).isInstanceOf(OutputTarget.Edge.class);
        assertThat(new KryoCodec(List.of(), false).deserialize(released.payload(), Object.class))
                .as("the handoff carried into the task-commit barrier")
                .isEqualTo("mid:v");
    }

    @Test
    @DisplayName("A timer inside a workflow tasklet fires: its registration reaches the effect, as in a stream")
    void aTimerInsideAWorkflowTaskletFires() {
        WorkflowOperativeClassRegistry.registerClass(TimerWorkflow.class);
        ItemEffect effect = soleEffect(work("timer_workflow", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.timers())
                .as("the registration is the core's to commit and arm")
                .containsExactly(TimerOp.register(TimerKind.PROCESSING_TIME, 1_001));
    }

    @Test
    @DisplayName("The retired wake method does not compile: WorkflowOperative declares no wakeAfter")
    void theRetiredWakeMethodDoesNotCompile() {
        assertThat(java.util.Arrays.stream(com.flezha.sdk.operative.WorkflowOperative.class.getMethods())
                        .map(java.lang.reflect.Method::getName))
                .doesNotContain("wakeAfter");
    }

    /** A workflow whose one tasklet registers a processing-time timer. */
    public static final class TimerWorkflow implements com.flezha.sdk.operative.WorkflowOperative {
        @Override
        public String factoryId() {
            return "timer_workflow";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed").into(TimerAfterTask.class);
        }
    }

    public static final class TimerAfterTask extends Task<Void> {
        public TimerAfterTask() {
            super("timer_after_task", (input, ctx) -> ctx.timerAfter(Duration.ofSeconds(1)));
        }
    }

    /** A DURABLE structured chain that declares nothing about its handoff. */
    public static final class DurableHandoffWorkflow implements com.flezha.sdk.operative.WorkflowOperative {
        @Override
        public String factoryId() {
            return "durable_handoff_workflow";
        }

        @Override
        public void channels(com.flezha.sdk.channel.ChannelBuilder c) {
            c.input(String.class, "seed")
                    .into(MidTask.class)
                    .named("wf_durable")
                    .into(KeyRecorder.class);
        }
    }

    @Test
    @DisplayName("CompensateRun decodes the recorded outputs and collects the undo emits")
    void compensateRunUndoesWithDecodedOutputs() {
        WorkflowOperativeClassRegistry.registerClass(SagaWorkflow.class);
        ItemEffect forward = soleEffect(work("saga_workflow", 0, Optional.empty(), 0, input(0, "job", 1)));
        assertThat(forward.outputs()).hasSize(1);

        ActivationOutcome out = outcome(command(
                "saga_workflow",
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
