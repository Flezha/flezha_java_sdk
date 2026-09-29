package com.flezha.sdk.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock a tasklet's declaration yields, and the registration-time refusals for clocks and
 * keyed channels: caught at registration, before any input is served and before anything durable
 * exists.
 */
class ClockAndKeyedValidationTest {

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("The default clock is the wall clock")
    void theDefaultClockIsTheWallClock() {
        UnboundedOperatorClassRegistry.registerClass(KeyedPluginOperator.class);
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology("clock-keyed-plugin");
        assertThat(topology.tasklet(0).clock()).isEqualTo(ClockKind.WALL);
    }

    @Test
    @DisplayName("Writing eventTime makes the clock event time")
    void writingEventTimeMakesTheClockEventTime() {
        UnboundedOperatorClassRegistry.registerClass(ExtractorOperator.class);
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology("clock-extractor");
        assertThat(topology.tasklet(0).clock()).isEqualTo(ClockKind.EVENT_TIME);
    }

    @Test
    @DisplayName("Two clocks are refused: eventTime written and logicalClock declared")
    void twoClocksAreRefused() {
        FlezhaException refused = Refused.by(
                "topology.two_clocks", () -> UnboundedOperatorClassRegistry.registerClass(TwoClocksOperator.class));
        assertThat(Refused.details(refused, "topology.two_clocks")).containsEntry("tasklet", "two_clocks");
    }

    @Test
    @DisplayName("A logical clock without state is refused as topology.unservable_clock")
    void aLogicalClockWithoutStateIsRefused() {
        Refused.by(
                "topology.unservable_clock",
                () -> UnboundedOperatorClassRegistry.registerClass(UnservableClockOperator.class));
    }

    @Test
    @DisplayName("a keyed channel with no plugin wire is refused")
    void keyedChannelWithoutPluginWireIsRefused() {
        Refused.by(
                "topology.keyed_channel_without_plugin_wire",
                () -> UnboundedOperatorClassRegistry.registerClass(KeyedNoPluginOperator.class));
    }

    @Test
    @DisplayName("a keyed plugin-bound channel registers and is marked keyed on the descriptor")
    void keyedPluginChannelRegisters() {
        UnboundedOperatorClassRegistry.registerClass(KeyedPluginOperator.class);
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology("clock-keyed-plugin");
        assertThat(topology).isNotNull();
        ChannelDescriptor out = topology.channel(topology.lookupChannelId("out"));
        assertThat(out.keyed()).isTrue();
    }

    @Test
    @DisplayName("a constructor that throws is reported as the author's error, not as a missing constructor")
    void throwingConstructorIsReportedAsTheAuthorsError() {
        Throwable thrown =
                catchThrowable(() -> UnboundedOperatorClassRegistry.registerClass(ThrowingConstructorOperator.class));

        assertThat(thrown).hasMessageNotContaining("no-arg constructor");
        assertThat(Arrays.asList(thrown, thrown.getCause()))
                .as("the author's exception is the one thrown or its direct cause")
                .anySatisfy(t ->
                        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage(AUTHORS_REFUSAL));
    }

    // --- fixtures -----------------------------------------------------------

    public static final class ThrowingConstructorOperator implements UnboundedOperator {
        public ThrowingConstructorOperator() {
            throw new IllegalStateException(AUTHORS_REFUSAL);
        }

        @Override
        public String factoryId() {
            return "clock-throwing-constructor";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class TwoClocksOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-two-clocks";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(TwoClocksTask.class);
        }
    }

    public static final class ExtractorOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-extractor";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(ExtractorTask.class);
        }
    }

    public static final class UnservableClockOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-unservable";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(UnservableClockTask.class);
        }
    }

    public static final class KeyedNoPluginOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-keyed-no-plugin";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var sPlainTask = c.input(String.class, "in").into(PlainTask.class);
            sPlainTask.named("mid").keyed().into(SecondTask.class);
        }
    }

    public static final class KeyedPluginOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-keyed-plugin";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var sPlainTask = c.input(String.class, "in").into(PlainTask.class);
            sPlainTask.keyed().sink("out");
        }
    }

    public static class Unit {}

    abstract static class Base<S> implements Tasklet<String, S, String> {
        @Override
        public void run(String input, Ctx<S, String> ctx) throws TaskletException {}
    }

    @Tasklet.Spec(name = "with_extractor")
    public static final class ExtractorTask extends Base<Void> {
        @Override
        public long eventTime(String input) {
            return Long.parseLong(input);
        }
    }

    /** Writes eventTime and declares a logical clock: one tasklet, two clocks. */
    @Tasklet.Spec(name = "two_clocks", logicalClock = true)
    public static final class TwoClocksTask extends Base<Unit> {
        @Override
        public long eventTime(String input) {
            return Long.parseLong(input);
        }
    }

    /** A logical clock over a Void state: there is no durable seq to serve. */
    @Tasklet.Spec(name = "unservable", logicalClock = true)
    public static final class UnservableClockTask extends Base<Void> {}

    @Tasklet.Spec(name = "plain_op")
    public static class PlainTask extends Base<Void> {}

    @Tasklet.Spec(name = "second_op")
    public static final class SecondTask extends PlainTask {}
}
