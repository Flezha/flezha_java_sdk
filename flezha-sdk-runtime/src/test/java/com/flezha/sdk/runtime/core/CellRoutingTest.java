package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Routing a payload to its Cell: the fused transform chain, the keys a
 * KeyRequest answers, static and dynamic cell modes, merged entry channels
 * and the Edge outputs an inter-tasklet emit becomes.
 */
class CellRoutingTest extends DispatchTestBase {

    @Test
    @DisplayName("a transform chain is one fused closure; KeyRequest answers the key of the MAPPED value")
    void transformChainAndKeyRequest() {
        RelayClassRegistry.registerClass(DynamicChainRelay.class);

        ActivationOutcome prepared = outcome(command(
                "dyn_chain",
                0,
                Optional.empty(),
                new ActivationKind.KeyRequest(
                        0,
                        List.of("AAPL:3".getBytes(StandardCharsets.UTF_8), "#c".getBytes(StandardCharsets.UTF_8)),
                        true,
                        false)));
        List<PrepareResult> answers = ((ActivationOutcome.Prepared) prepared).answers();
        assertThat(answers).hasSize(2);
        assertThat(keysAnswered(answers.get(0))).containsExactly("AAPL");
        assertThat(keysAnswered(answers.get(1)))
                .as("the filtered value yields no key")
                .isEmpty();

        ItemEffect effect = soleEffect(work("dyn_chain", 0, Optional.of("AAPL"), 0, input(0, "AAPL:3", 1)));
        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(RECORDED).containsExactly("AAPL=AAPLx3");
        assertThat(TRAVERSALS)
                .as("prepare + work each traverse the ONE fused closure once per payload")
                .hasValue(3);

        soleEffect(work("dyn_chain", 0, Optional.of("MSFT"), 0, input(0, "MSFT:7", 2)));
        assertThat(RECORDED).containsExactly("AAPL=AAPLx3", "MSFT=MSFTx7");
    }

    @Test
    @DisplayName("a filtered-out payload consumes its offset with no invocation")
    void filteredPayloadStillAcks() {
        RelayClassRegistry.registerClass(DynamicChainRelay.class);
        ItemEffect effect = soleEffect(
                work("dyn_chain", 0, Optional.of("AAPL"), 0, input(0, "#comment", 1, OptionalLong.of(4), false)));
        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.consumedOffset()).hasValue(4);
        assertThat(RECORDED).isEmpty();
    }

    @Test
    @DisplayName("flatMap fans one payload across keys; each Cell runs only its own prepared value")
    void fanOutKeysEveryYieldedValue() {
        RelayClassRegistry.registerClass(FanOutRelay.class);

        ActivationOutcome prepared = outcome(command(
                "fan_out",
                0,
                Optional.empty(),
                new ActivationKind.KeyRequest(0, List.of("AAPL:3".getBytes(StandardCharsets.UTF_8)), true, false)));
        PrepareResult answer = ((ActivationOutcome.Prepared) prepared).answers().get(0);
        assertThat(keysAnswered(answer)).containsExactly("AAPL@1m", "AAPL@5m");

        List<byte[]> mine = ((PrepareResult.Ok) answer)
                .answer().values().stream()
                        .filter(value -> value.keys().contains("AAPL@5m"))
                        .map(PrepareAnswer.Value::bytes)
                        .toList();
        soleEffect(work("fan_out", 0, Optional.of("AAPL@5m"), 0, prepared("not decoded", mine)));
        assertThat(RECORDED).containsExactly("AAPL@5m=AAPL@5m");
    }

    @Test
    @DisplayName("Instance state does not leak between Cell keys")
    void staticCellsUsePerCellInstances() {
        RelayClassRegistry.registerClass(StaticRelay.class);

        soleEffect(work("static_op", 0, Optional.of("a"), 0, input(0, "a:1", 1)));
        soleEffect(work("static_op", 0, Optional.of("a"), 0, input(0, "a:2", 2)));
        soleEffect(work("static_op", 0, Optional.of("b"), 0, input(0, "b:1", 3)));

        assertThat(RECORDED).containsExactly("a#1=a:1", "a#2=a:2", "b#1=b:1");

        ActivationOutcome prepared = outcome(command(
                "static_op",
                0,
                Optional.empty(),
                new ActivationKind.KeyRequest(0, List.of("b:9".getBytes(StandardCharsets.UTF_8)), true, false)));
        assertThat(keysAnswered(
                        ((ActivationOutcome.Prepared) prepared).answers().get(0)))
                .containsExactly("b");
    }

    @Test
    @DisplayName("merged entry channels each apply their own mapping at delivery")
    void mergedInputAppliesEdgeMapping() {
        RelayClassRegistry.registerClass(MergeRelay.class);
        soleEffect(work("merge_op", 0, Optional.empty(), 0, input(0, "x", 1)));
        soleEffect(work("merge_op", 0, Optional.empty(), 0, input(1, "y", 2)));
        assertThat(RECORDED).containsExactly("=A:x", "=B:y");
    }

    @Test
    @DisplayName("an inter-task emit becomes per-consumer Edge outputs carrying the post-transform value")
    void interTaskEmitBecomesEdgeOutputs() {
        RelayClassRegistry.registerClass(PipelineRelay.class);
        ItemEffect effect = soleEffect(work("pipeline_op", 0, Optional.empty(), 0, input(0, "v", 1)));

        assertThat(effect.outputs()).hasSize(1);
        Output edge = effect.outputs().get(0);
        assertThat(edge.target()).isEqualTo(new OutputTarget.Edge(1, 1));
        assertThat(edge.key()).isEmpty();
        assertThat(edge.eventTime()).isEmpty();

        // The Edge payload round-trips through the consumer's Work dispatch.
        ItemEffect consumed = soleEffect(
                work("pipeline_op", 1, Optional.empty(), 0, handoff(edge.payload(), WorkItem.Form.TRANSFORMED)));
        assertThat(consumed.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(RECORDED).containsExactly("=mid:v");
    }

    @Test
    @DisplayName("an FLOW inter-task edge map applies exactly once, producer-side")
    void interTaskEdgeMapAppliesOnce() {
        RelayClassRegistry.registerClass(MappedPipelineRelay.class);
        ItemEffect produced = soleEffect(work("mapped_pipeline_op", 0, Optional.empty(), 0, input(0, "v", 1)));
        Output edge = produced.outputs().get(0);
        soleEffect(
                work("mapped_pipeline_op", 1, Optional.empty(), 0, handoff(edge.payload(), WorkItem.Form.TRANSFORMED)));

        // collectEdgeOutputs applied the chain when the producing activation
        // completed, so the payload crosses already mapped and delivery adds
        // nothing. Every other transform in the tree sits on an ENTRY channel,
        // where the producer is the wire and delivery IS the one application.
        assertThat(RECORDED).containsExactly("=M:mid:v");
    }

    @Test
    @DisplayName("the input form comes from the core: only an untransformed payload runs the edge")
    void theInputFormComesFromTheCore() {
        RelayClassRegistry.registerClass(MappedPipelineRelay.class);
        Output edge = soleEffect(work("mapped_pipeline_op", 0, Optional.empty(), 0, input(0, "v", 1)))
                .outputs()
                .get(0);
        RECORDED.clear();
        for (WorkItem.Form form : List.of(WorkItem.Form.UNTRANSFORMED, WorkItem.Form.TRANSFORMED)) {
            soleEffect(work("mapped_pipeline_op", 1, Optional.empty(), 0, handoff(edge.payload(), form)));
        }

        // Whatever the relay's run: an entry payload and a steps release come
        // untransformed, a stream producer's output transformed.
        assertThat(RECORDED).containsExactly("=M:M:mid:v", "=M:mid:v");
    }

    @Test
    @DisplayName("an inter-task consumer narrows on the once-transformed value, by key and by cellsFor")
    void interTaskConsumersNarrowOnTheTransformedValue() {
        RelayClassRegistry.registerClass(MappedStaticPipelineRelay.class);
        RelayClassRegistry.registerClass(MappedKeyedPipelineRelay.class);

        // cellsFor: the core cannot narrow an unkeyed edge output to a
        // static Cell, so it fans and delivery narrows. `a:mid:1` names
        // Cell `a` and no other.
        Output fanned = soleEffect(work("mapped_static_op", 0, Optional.empty(), 0, input(0, "1", 1)))
                .outputs()
                .get(0);
        deliver("mapped_static_op", 1, "a", fanned);
        deliver("mapped_static_op", 1, "b", fanned);
        assertThat(RECORDED).containsExactly("a#1=a:mid:1");

        RECORDED.clear();
        // The edge key: the producer already computed it and the core routed
        // on it, so re-reading it at delivery must agree.
        Output keyed = soleEffect(work("mapped_keyed_op", 0, Optional.empty(), 0, input(0, "v", 1)))
                .outputs()
                .get(0);
        assertThat(keyed.key()).hasValue("mid");
        deliver("mapped_keyed_op", 1, "mid", keyed);
        deliver("mapped_keyed_op", 1, "other", keyed);
        assertThat(RECORDED).containsExactly("mid=K:mid:v");
    }

    /** Hand one produced Edge output to `cellKey`'s cell of its consumer. */
    private void deliver(String factory, int tasklet, String cellKey, Output produced) {
        soleEffect(work(
                factory, tasklet, Optional.of(cellKey), 0, handoff(produced.payload(), WorkItem.Form.TRANSFORMED)));
    }

    @Test
    @DisplayName("KeyRequest answers the extractor's event time; a failing parse is a per-payload Err")
    void prepareEventTimeAndPerPayloadFailure() {
        RelayClassRegistry.registerClass(TimerRelay.class);
        ActivationOutcome prepared = outcome(command(
                "timer_op",
                0,
                Optional.empty(),
                new ActivationKind.KeyRequest(
                        0,
                        List.of("1234".getBytes(StandardCharsets.UTF_8), "nope".getBytes(StandardCharsets.UTF_8)),
                        false,
                        true)));
        List<PrepareResult> answers = ((ActivationOutcome.Prepared) prepared).answers();
        assertThat(((PrepareResult.Ok) answers.get(0)).answer().eventTime()).hasValue(1234);
        assertThat(answers.get(1)).isInstanceOf(PrepareResult.Err.class);
    }
}
