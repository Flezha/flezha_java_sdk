package com.flezha.sdk.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.operator.builder.OperatorTopologyBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
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
        OperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("default policy: DROP_NOTHING on entry and inter-tasklet channels alike")
    void defaultPoliciesByKind() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new TwoStageOperator());
        // Channel index 0 is the entry channel "input"; index 1 is the
        // inter-tasklet channel "mid".
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NOTHING);
        assertThat(topology.channels().get(1).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NOTHING);
    }

    @Test
    @DisplayName("onFull(DROP_OLDEST) overrides the per-kind default on an inter-tasklet channel")
    void onFullOverridesDefault() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new InterTaskletDropOldestOperator());
        assertThat(topology.channels().get(1).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_OLDEST);
    }

    @Test
    @DisplayName("onFull(DROP_NEWEST) on an entry channel overrides the default: shedding is declared")
    void onFullOnEntryChannelOverrides() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new EntryDropNewestOperator());
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NEWEST);
    }

    @Test
    @DisplayName("a written Tasklet.mailboxCapacity() flows into the descriptor")
    void taskletOverrideHonored() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new BigMailboxOperator());
        int taskletId = topology.taskletNameToId().get("big_mailbox_tasklet");
        assertThat(topology.tasklets().get(taskletId).mailboxCapacity()).isEqualTo(8192);
    }

    @Test
    @DisplayName("default mailbox capacity is 1024 when no override and no env var present")
    void defaultMailboxCapacity() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new TwoStageOperator());
        for (TaskletDescriptor desc : topology.tasklets()) {
            assertThat(desc.mailboxCapacity()).isEqualTo(1024);
        }
    }

    @Test
    @DisplayName("capacity = 0 fails registration, named by the core")
    void zeroCapacityRejected() {
        FlezhaException refused = Refused.by(
                "topology.mailbox_capacity_zero", () -> OperatorTopologyBuilder.build(new ZeroCapacityOperator()));
        assertThat(Refused.details(refused, "topology.mailbox_capacity_zero"))
                .containsEntry("tasklet", "zero_capacity_tasklet");
    }

    @Test
    @DisplayName("mailboxCapacity above MAX is rejected, named by the core with the value")
    void tooLargeRejected() {
        FlezhaException refused = Refused.by(
                "topology.mailbox_capacity_too_large", () -> OperatorTopologyBuilder.build(new HugeCapacityOperator()));
        assertThat(Refused.details(refused, "topology.mailbox_capacity_too_large"))
                .containsEntry("tasklet", "huge_capacity_tasklet")
                .containsEntry("value", 9999999);
    }

    // ---- Test tasklets / operators ----------------------------------------------------

    @Tasklet.Spec(name = "a")
    public static final class A implements Tasklet<String, Void, Integer> {

        @Override
        public void run(String input, Ctx<Void, Integer> ctx) {}
    }

    @Tasklet.Spec(name = "b")
    public static final class B implements Tasklet<Integer, Void, Void> {

        @Override
        public void run(Integer input, Ctx<Void, Void> ctx) {}
    }

    public static final class TwoStageOperator implements Operator {
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

    public static final class InterTaskletDropOldestOperator implements Operator {
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
        FlezhaException refused = Refused.by(
                "topology.dropping_policy_under_effectively_once",
                () -> OperatorTopologyBuilder.build(new TierShedsOperator()));
        assertThat(Refused.details(refused, "topology.dropping_policy_under_effectively_once"))
                .containsEntry("channel", "input")
                .containsEntry("policy", "drop_newest");
    }

    @Test
    @DisplayName("shedding stays available to a class that declares neither the tier nor a correction")
    void droppingPolicyOffTheTierStillBuilds() {
        OperatorTopology topology = OperatorTopologyBuilder.build(new EntryDropNewestOperator());
        assertThat(topology.channels().get(0).overflowPolicy()).isEqualTo(OverflowPolicy.DROP_NEWEST);
    }

    public static final class TierShedsOperator implements Operator {
        @Override
        public String factoryId() {
            return "tier_sheds";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce());
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NEWEST)
                    .into(A.class)
                    .keyed()
                    .sink("out");
        }
    }

    public static final class EntryDropNewestOperator implements Operator {
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
    @Tasklet.Spec(name = "big_mailbox_tasklet", capacity = 8192)
    public static final class BigMailboxTasklet implements Tasklet<String, Void, Integer> {

        @Override
        public void run(String input, Ctx<Void, Integer> ctx) {}
    }

    public static final class BigMailboxOperator implements Operator {
        @Override
        public String factoryId() {
            return "big_mailbox";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(BigMailboxTasklet.class);
        }
    }

    @Tasklet.Spec(name = "zero_capacity_tasklet", capacity = 0)
    public static final class ZeroCapacityTasklet implements Tasklet<String, Void, Integer> {
        @Override
        public void run(String input, Ctx<Void, Integer> ctx) {}
    }

    public static final class ZeroCapacityOperator implements Operator {
        @Override
        public String factoryId() {
            return "zero_cap";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(ZeroCapacityTasklet.class);
        }
    }

    @Tasklet.Spec(name = "huge_capacity_tasklet", capacity = 9_999_999)
    public static final class HugeCapacityTasklet implements Tasklet<String, Void, Integer> {

        @Override
        public void run(String input, Ctx<Void, Integer> ctx) {}
    }

    public static final class HugeCapacityOperator implements Operator {
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
