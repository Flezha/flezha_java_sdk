package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The emit funnel: the Change and plugin output an invocation produces, the
 * key a keyed channel's output names, the two keyed-emit refusals, the timer
 * ops that ride the effect, the checkpoint mark and the completion request.
 */
class EmitContextTest extends DispatchTestBase {

    @Test
    @DisplayName("an input applies its Change, ships decodable change bytes, and stages the plugin output")
    void workAppliesChangesAndCollectsOutputs() {
        RelayClassRegistry.registerClass(CounterRelay.class);
        ItemEffect effect =
                soleEffect(work("counter_op", 0, Optional.empty(), 7, input(0, "x", 1_000, OptionalLong.of(9), false)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.consumedOffset()).hasValue(9);
        assertThat(effect.eventTime()).isEqualTo(1_000);
        assertThat(effect.checkpoint()).isFalse();
        assertThat(effect.changes()).hasSize(1);
        CountChange change = new KryoCodec(List.of(CountChange.class))
                .deserialize(effect.changes().get(0), CountChange.class);
        assertThat(change.delta).isEqualTo(1);

        assertThat(effect.outputs()).hasSize(1);
        Output output = effect.outputs().get(0);
        assertThat(output.target()).isEqualTo(new OutputTarget.Plugin("out"));
        assertThat(output.key()).isEmpty();
        assertThat(output.metadata())
                .as("non-keyed channel carries no identity stamps")
                .isEmpty();
        assertThat(new String(output.payload(), StandardCharsets.UTF_8)).isEqualTo("seen:x");
    }

    @Test
    @DisplayName("the output names the channel the plugin output is addressed by")
    void outputNamesTheChannel() {
        RelayClassRegistry.registerClass(SinkNamesChannelRelay.class);
        ItemEffect effect = soleEffect(work("sink_names_channel", 0, Optional.empty(), 5, input(0, "v", 1_000)));

        assertThat(effect.outputs()).hasSize(1);
        assertThat(effect.outputs().get(0).target())
                .as("`.output(\"items_output\")` with no `.named(...)` names the channel")
                .isEqualTo(new OutputTarget.Plugin("items_output"));
    }

    @Test
    @DisplayName("a keyed emit names its key and carries the user's metadata only")
    void keyedEmitNamesItsKey() {
        RelayClassRegistry.registerClass(KeyedEmitRelay.class);
        ItemEffect effect = soleEffect(work("keyed_op", 0, Optional.empty(), 5, input(0, "v", 1_000)));

        assertThat(effect.outputs()).hasSize(1);
        Output output = effect.outputs().get(0);
        assertThat(output.key()).contains("k-v");
        assertThat(metaMap(output)).isEqualTo(Map.of("user", "meta"));
    }

    @Test
    @DisplayName("A key-less emit in a keyed Cell carries that Cell's key")
    void keyLessEmitInAKeyedCellCarriesTheCellKey() {
        RelayClassRegistry.registerClass(KeyedEmitRelay.class);
        ItemEffect effect = soleEffect(work("keyed_op", 0, Optional.of("BTC/USD"), 5, input(0, "plain:v", 1_000)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effect.outputs()).hasSize(1);
        Output output = effect.outputs().get(0);
        assertThat(output.key()).contains("BTC/USD");
    }

    @Test
    @DisplayName("a key-less emit on a keyed channel is refused where the Cell has no key")
    void keyLessEmitOnAKeyedChannelIsRefusedWithoutAnCellKey() {
        RelayClassRegistry.registerClass(KeyedEmitRelay.class);
        ItemEffect effect = soleEffect(work("keyed_op", 0, Optional.empty(), 5, input(0, "plain:v", 1_000)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(((Verdict.RetryableError) effect.verdict()).message())
                .contains("runtime.key_required_on_keyed_channel");
        assertThat(effect.outputs()).as("nothing may be emitted").isEmpty();
    }

    @Test
    @DisplayName("emitKeyed on a non-keyed channel is refused, cell key or not")
    void keyedEmitOnANonKeyedChannelIsRefused() {
        RelayClassRegistry.registerClass(CounterRelay.class);
        ItemEffect effect = soleEffect(work("counter_op", 0, Optional.of("BTC/USD"), 7, input(0, "key:x", 1_000)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(((Verdict.RetryableError) effect.verdict()).message())
                .contains("runtime.key_not_allowed_on_non_keyed_channel");
        assertThat(effect.outputs()).as("nothing may be emitted").isEmpty();
    }

    @Test
    @DisplayName(
            "registrations ride the effect; a firing leaves its removal to the core; now() equals the scheduled time")
    void timerOpsRideTheEffect() {
        RelayClassRegistry.registerClass(TimerRelay.class);
        ItemEffect armed = soleEffect(work("timer_op", 0, Optional.empty(), 0, input(0, "1000", 1_000)));
        assertThat(armed.timers())
                .containsExactly(
                        TimerOp.register(TimerKind.EVENT_TIME, 1_010),
                        TimerOp.register(TimerKind.PROCESSING_TIME, 1_005));

        ItemEffect fired = soleEffect(work(
                "timer_op", 0, Optional.empty(), 0, new WorkItem.Timer(new TimerFiring(TimerKind.EVENT_TIME, 1_010))));
        assertThat(fired.eventTime()).isEqualTo(1_010);
        assertThat(fired.timers())
                .as("the chained timerAfter anchored on the scheduled time")
                .containsExactly(TimerOp.register(TimerKind.PROCESSING_TIME, 1_017));
        assertThat(TIMER_EVENTS).containsExactly("EVENT_TIME@1010 now=1010");
    }

    @Test
    @DisplayName("an emit from a tasklet that owns no output channel is refused naming the tasklet")
    void anEmitWithNoOutputChannelIsRefused() {
        RelayClassRegistry.registerClass(LeafEmitRelay.class);
        ItemEffect effect = soleEffect(work("leaf_emit_op", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(((Verdict.RetryableError) effect.verdict()).message()).contains("runtime.no_output_channel");
        assertThat(effect.outputs()).isEmpty();
    }

    @Test
    @DisplayName("ctx.checkpoint() marks the effect")
    void checkpointFlagAndTierOutputs() {
        RelayClassRegistry.registerClass(CheckpointRelay.class);
        ItemEffect open = soleEffect(work("ckpt_op", 0, Optional.empty(), 0, input(0, "accumulate", 1)));
        assertThat(open.checkpoint()).isFalse();
        assertThat(open.outputs()).hasSize(1);
        assertThat(open.outputs().get(0).key()).contains("ck");

        ItemEffect closing = soleEffect(work("ckpt_op", 0, Optional.empty(), 0, input(0, "commit", 2)));
        assertThat(closing.checkpoint()).isTrue();
    }

    @Test
    @DisplayName("a flow tasklet's complete rides its effect the same way, for the core to end the instance")
    void aFlowTaskletsCompleteRidesItsEffect() {
        RelayClassRegistry.registerClass(CompletingRelay.class);
        ItemEffect effect = soleEffect(work("completing_flow", 0, Optional.empty(), 0, input(0, "x", 1)));

        assertThat(effect.verdict())
                .as("the host does not judge the run; the core ends the flow instance once its Cells are idle")
                .isInstanceOf(Verdict.Ok.class);
        assertThat(effect.complete())
                .hasValueSatisfying(result ->
                        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("done:x"));
    }
}
