package com.flezha.sdk.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.LogicalClock;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Registration-time refusals for the clock hierarchy and keyed channels.
 *
 * <p>Rust catches all of these at compile time (a {@code TimeExtractor<Input>}
 * bound, {@code BuildTopologyError::UnservableClock}, a keyed channel with no
 * plugin wire). Java's erasure precludes the compile-time form, so the same
 * defects are caught here — at registration, before any input is served and
 * before anything durable exists.
 */
class ClockAndKeyedValidationTest {

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("EventTimeFrom without an eventTime override is refused")
    void eventTimeFromWithoutExtractorIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(NoExtractorOperator.class))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("no_extractor")
                .hasMessageContaining("eventTime");
    }

    @Test
    @DisplayName("EventTimeFrom WITH an eventTime override registers")
    void eventTimeFromWithExtractorRegisters() {
        UnboundedOperatorClassRegistry.registerClass(ExtractorOperator.class);
        assertThat(UnboundedOperatorClassRegistry.getTopology("clock-extractor"))
                .isNotNull();
    }

    @Test
    @DisplayName("LogicalClock with NoChange is refused as UnservableClock")
    void logicalClockWithNoChangeIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(UnservableClockOperator.class))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("UnservableClock")
                .hasMessageContaining("with `type Change = NoChange`");
    }

    @Test
    @DisplayName("a keyed channel with no plugin wire is refused")
    void keyedChannelWithoutPluginWireIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(KeyedNoPluginOperator.class))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("KeyedChannelWithoutPluginWire")
                .hasMessageContaining("is declared .keyed() but has no plugin wire");
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

    public static final class NoExtractorOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "clock-no-extractor";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(NoExtractorTask.class);
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

    public record Unit() {}

    abstract static class Base<C extends Change, K extends com.flezha.sdk.state.Clock>
            implements Tasklet<String, Unit, String, C, K, Void> {
        @Override
        public Unit initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Unit();
        }

        @Override
        public void run(String input, StateHandle<Unit, C> state, TaskletCtx ctx) throws TaskletException {}
    }

    /** Declares EventTimeFrom but never overrides eventTime — the refused case. */
    public static final class NoExtractorTask extends Base<NoChange, EventTimeFrom> {
        @Override
        public String name() {
            return "no_extractor";
        }
    }

    public static final class ExtractorTask extends Base<NoChange, EventTimeFrom> {
        @Override
        public String name() {
            return "with_extractor";
        }

        @Override
        public long eventTime(String input) {
            return Long.parseLong(input);
        }
    }

    /** LogicalClock + NoChange: there is no durable seq to serve. */
    public static final class UnservableClockTask extends Base<NoChange, LogicalClock> {
        @Override
        public String name() {
            return "unservable";
        }
    }

    public static class PlainTask extends Base<NoChange, WallClock> {
        @Override
        public String name() {
            return "plain_op";
        }
    }

    public static final class SecondTask extends PlainTask {
        @Override
        public String name() {
            return "second_op";
        }
    }
}
