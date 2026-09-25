package com.flezha.sdk.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Topology-level tests for the bounded-mailbox surface added by the
 * Rust→Java parity port: {@code OverflowPolicy} + {@code .onFull(...)} +
 * {@code Tasklet.mailboxCapacity()} + {@code FLEZHA_MAILBOX_CAPACITY} env
 * resolution + the two range refusals the core names off the resolved value.
 */
class BoundedMailboxTopologyTest {

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("default policy: DROP_NOTHING on entry and inter-tasklet channels alike")
    void defaultPoliciesByKind() {
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new TwoStageUnboundedOperator());
        // Channel index 0 is the entry channel "input"; index 1 is the
        // inter-tasklet channel "mid".
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NOTHING);
        assertThat(topology.channels().get(1).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NOTHING);
    }

    @Test
    @DisplayName("onFull(DROP_OLDEST) overrides the per-kind default on an inter-tasklet channel")
    void onFullOverridesDefault() {
        UnboundedOperatorTopology topology =
                UnboundedOperatorTopologyBuilder.build(new InterTaskletDropOldestUnboundedOperator());
        assertThat(topology.channels().get(1).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_OLDEST);
    }

    @Test
    @DisplayName("onFull(DROP_NEWEST) on an entry channel overrides the default: shedding is declared")
    void onFullOnEntryChannelOverrides() {
        UnboundedOperatorTopology topology =
                UnboundedOperatorTopologyBuilder.build(new EntryDropNewestUnboundedOperator());
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NEWEST);
    }

    @Test
    @DisplayName("a written Tasklet.mailboxCapacity() flows into the descriptor")
    void taskletOverrideHonored() {
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new BigMailboxUnboundedOperator());
        int taskletId = topology.taskletNameToId().get("big_mailbox_tasklet");
        assertThat(topology.tasklets().get(taskletId).mailboxCapacity()).isEqualTo(8192);
    }

    @Test
    @DisplayName("default mailbox capacity is 1024 when no override and no env var present")
    void defaultMailboxCapacity() {
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new TwoStageUnboundedOperator());
        for (TaskletDescriptor desc : topology.tasklets()) {
            assertThat(desc.mailboxCapacity()).isEqualTo(1024);
        }
    }

    @Test
    @DisplayName("mailboxCapacity = 0 fails registration, named by the core")
    void zeroCapacityRejected() {
        assertThatThrownBy(() -> UnboundedOperatorTopologyBuilder.build(new ZeroCapacityUnboundedOperator()))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("MailboxCapacityZero")
                .hasMessageContaining("tasklet `zero_capacity_tasklet` declared mailbox_capacity = 0");
    }

    @Test
    @DisplayName("mailboxCapacity above MAX is rejected, named by the core with the value")
    void tooLargeRejected() {
        assertThatThrownBy(() -> UnboundedOperatorTopologyBuilder.build(new HugeCapacityUnboundedOperator()))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("MailboxCapacityTooLarge")
                .hasMessageContaining("huge_capacity_tasklet")
                .hasMessageContaining("9999999");
    }

    // ---- Test tasklets / operators ----------------------------------------------------

    public static final class A extends StatelessTestTasklet<String, Object, Integer> {
        @Override
        public String name() {
            return "a";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class B extends StatelessTestTasklet<Integer, Object, Void> {
        @Override
        public String name() {
            return "b";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(Integer input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class TwoStageUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "two_stage";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var sA = c.input(String.class, "input").into(A.class);
            sA.named("mid").into(B.class);
        }
    }

    public static final class InterTaskletDropOldestUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "interprocedure_drop_oldest";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var sA = c.input(String.class, "input").into(A.class);
            sA.named("mid").onFull(OverflowPolicy.DROP_OLDEST).into(B.class);
        }
    }

    /**
     * Promising effectively-once and asking to shed is a contradiction, not a
     * trade-off: a dropped input's offset is still advanced past by the tier's
     * watermark, so the loss is permanent under a contract that says nothing
     * is lost. Refused at declare time, where the author can still see it.
     */
    @Test
    @DisplayName("a dropping policy under effectivelyOnce() is refused, naming the channel")
    void droppingPolicyUnderTheTierIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorTopologyBuilder.build(new TierShedsUnboundedOperator()))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("DroppingPolicyUnderEffectivelyOnce")
                .hasMessageContaining("channel `input` declares .on_full(drop_newest)")
                .hasMessageContaining("effectively_once()");
    }

    @Test
    @DisplayName("shedding stays available to a class that declares neither the tier nor a correction")
    void droppingPolicyOffTheTierStillBuilds() {
        UnboundedOperatorTopology topology =
                UnboundedOperatorTopologyBuilder.build(new EntryDropNewestUnboundedOperator());
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NEWEST);
    }

    public static final class TierShedsUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "tier_sheds";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NEWEST)
                    .into(A.class)
                    .keyed()
                    .sink("out");
        }
    }

    public static final class EntryDropNewestUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "entry_drop_newest";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var sA = c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NEWEST)
                    .into(A.class);
            sA.named("mid").into(B.class);
        }
    }

    // Custom Tasklet with an explicit mailboxCapacity() override.
    public static final class BigMailboxTasklet extends StatelessTestTasklet<String, Object, Integer> {
        @Override
        public String name() {
            return "big_mailbox_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public int mailboxCapacity() {
            return 8192;
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class BigMailboxUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "big_mailbox";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(BigMailboxTasklet.class);
        }
    }

    public static final class ZeroCapacityTasklet extends StatelessTestTasklet<String, Object, Integer> {
        @Override
        public String name() {
            return "zero_capacity_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public int mailboxCapacity() {
            return 0;
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class ZeroCapacityUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "zero_cap";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(ZeroCapacityTasklet.class);
        }
    }

    public static final class HugeCapacityTasklet extends StatelessTestTasklet<String, Object, Integer> {
        @Override
        public String name() {
            return "huge_capacity_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public int mailboxCapacity() {
            return 9_999_999;
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class HugeCapacityUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "huge_cap";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(HugeCapacityTasklet.class);
        }
    }
}
