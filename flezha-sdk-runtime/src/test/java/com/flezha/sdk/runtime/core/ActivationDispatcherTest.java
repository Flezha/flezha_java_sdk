package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.state.PendingTimers;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.SpawnOptions;
import com.flezha.sdk.tasklet.SpawnRequests;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher's own contract: routing an Activation to its instance, the
 * refusal when the factory is unknown, and the two flags an item carries into
 * the invocation.
 */
class ActivationDispatcherTest extends DispatchTestBase {

    @Test
    @DisplayName("verdict classification: NonRetryable vs retryable, stopping at the first failed item")
    void verdictClassificationStopsAtFirstFailure() {
        RelayClassRegistry.registerClass(PoisonRelay.class);
        ActivationOutcome out = outcome(work(
                "poison_op", 0, Optional.empty(), 0, input(0, "ok-1", 1), input(0, "bad", 2), input(0, "ok-2", 3)));
        List<ItemEffect> effects = ((ActivationOutcome.Work) out).effects();
        assertThat(effects)
                .as("processing stopped at the failed item, which is included")
                .hasSize(2);
        assertThat(effects.get(0).verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(effects.get(1).verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(RECORDED).containsExactly("ok-1");

        ItemEffect fatal = soleEffect(work("poison_op", 0, Optional.empty(), 0, input(0, "bad-fatal", 4)));
        assertThat(fatal.verdict()).isInstanceOf(Verdict.NonRetryableError.class);
        assertThat(((Verdict.NonRetryableError) fatal.verdict()).message()).contains("fatal");
    }

    @Test
    @DisplayName("an item sees the timers an earlier item of the same activation registered")
    void anItemSeesTheTimersOfAnEarlierItem() {
        RelayClassRegistry.registerClass(SetThenCancelRelay.class);
        ActivationOutcome out = outcome(
                work("set_then_cancel_op", 0, Optional.empty(), 0, input(0, "set", 100), input(0, "cancel", 100)));
        List<ItemEffect> effects = ((ActivationOutcome.Work) out).effects();

        assertThat(RECORDED).containsExactly("cancelled=true");
        assertThat(effects.get(1).timers()).containsExactly(TimerOp.cancel(TimerKind.PROCESSING_TIME, 1_100));
    }

    @Test
    @DisplayName("an item after a firing no longer finds the fired timer pending")
    void anItemAfterAFiringNoLongerFindsTheFiredTimer() {
        RelayClassRegistry.registerClass(SetThenCancelRelay.class);
        CoreCalls pendingAt1100 = new CoreCalls() {
            @Override
            public void part(long token, int item, byte[] payload) {}

            @Override
            public PendingTimers pendingTimers(long token) {
                return PendingTimers.ofPairs(new long[] {1, 1_100});
            }
        };
        ActivationResult result = new ActivationDispatcher(pendingAt1100)
                .dispatch(work(
                        "set_then_cancel_op",
                        0,
                        Optional.empty(),
                        0,
                        new WorkItem.Timer(TimerKind.PROCESSING_TIME, 1_100),
                        input(0, "cancel", 100)));

        assertThat(((ActivationOutcome.Work) result.outcome()).effects()).hasSize(2);
        assertThat(RECORDED).containsExactly("cancelled=false");
    }

    @Test
    @DisplayName("a payload the entry decoder rejects is Unreadable; a tasklet throwing on a readable one is retryable")
    void unreadableEntryPayloadIsToldApartFromATaskletFailure() {
        RelayClassRegistry.registerClass(TypedEntryRelay.class);

        ItemEffect unreadable = soleEffect(work("typed_entry_op", 0, Optional.empty(), 0, input(0, "not json", 1)));
        assertThat(unreadable.verdict()).isInstanceOf(Verdict.Unreadable.class);
        assertThat(((Verdict.Unreadable) unreadable.verdict()).message()).contains("entry channel");
        assertThat(unreadable.consumedOffset()).isEmpty();
        assertThat(RECORDED).as("no tasklet ran on an unreadable payload").isEmpty();

        ItemEffect thrown =
                soleEffect(work("typed_entry_op", 0, Optional.empty(), 0, input(0, "{\"text\":\"boom\"}", 2)));
        assertThat(thrown.verdict()).isInstanceOf(Verdict.RetryableError.class);

        ItemEffect fine = soleEffect(work("typed_entry_op", 0, Optional.empty(), 0, input(0, "{\"text\":\"ok\"}", 3)));
        assertThat(fine.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(RECORDED).containsExactly("ok");
    }

    @Test
    @DisplayName("an unknown factory fails the whole activation as structural")
    void unknownFactoryIsStructural() {
        ActivationOutcome out = outcome(work("no_such_factory", 0, Optional.empty(), 0, input(0, "x", 1)));
        assertThat(out).isInstanceOf(ActivationOutcome.Failed.class);
        WorkError error = ((ActivationOutcome.Failed) out).error();
        assertThat(error.nonRetryable()).isTrue();
        assertThat(error.message()).contains("no_such_factory");
    }

    @Test
    @DisplayName("a replayed item serves ctx.replaying() = true")
    void replayFlagReachesTheContext() {
        RelayClassRegistry.registerClass(ReplayProbeRelay.class);
        soleEffect(work("replay_probe", 0, Optional.empty(), 0, input(0, "x", 1, OptionalLong.empty(), true)));
        assertThat(RECORDED).containsExactly("replaying=true");
    }

    @Test
    @DisplayName("a spawn carries the activation's token and the item's replay flag and offset to the core")
    void aSpawnCarriesTheTokenAndTheReplayFlagAndOffset() {
        RelayClassRegistry.registerClass(SpawnProbeRelay.class);
        SpawnRequests.install(new SpawnRequests.Requester() {
            @Override
            public long request(
                    long token,
                    String factoryId,
                    String id,
                    byte[] payload,
                    String ifRunning,
                    boolean replay,
                    long offset) {
                RECORDED.add(token + "/" + factoryId + "/" + id + "/" + replay + "/" + offset);
                return 1;
            }

            @Override
            public String result(long handle, long timeoutMs) {
                return null;
            }
        });
        try {
            for (boolean replay : new boolean[] {false, true}) {
                OptionalLong offset = replay ? OptionalLong.empty() : OptionalLong.of(7);
                outcome(new Activation(
                        42,
                        new CellRef(OP, 0, Optional.empty()),
                        new ActivationKind.Work(List.of(input(0, "w1", 1, offset, replay))),
                        0,
                        "spawn_probe"));
            }
        } finally {
            SpawnRequests.uninstall();
        }
        assertThat(RECORDED)
                .containsExactly(
                        "42/worker//false/7", "42/worker/w1/false/7", "42/worker//true/-1", "42/worker/w1/true/-1");
    }

    @Test
    @DisplayName("a tasklet constructor that throws at materialization surfaces the author's error")
    void throwingTaskletConstructorAtMaterializationIsTheAuthorsError() {
        RelayClassRegistry.registerClass(RefusingRelay.class);
        REFUSE.set(true);

        assertThatThrownBy(() -> RelayInstance.materialize(OP, "refusing_op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
    }

    public static final class SpawnProbeRelay extends Op {
        public SpawnProbeRelay() {
            super("spawn_probe", c -> c.input(String.class, "in").into(SpawnProbeTask.class));
        }
    }

    /** Spawns once without an id and once under its input. */
    @Tasklet.Spec(name = "spawn_probe_task")
    public static final class SpawnProbeTask extends Task<Void> {
        public SpawnProbeTask() {
            super((input, ctx) -> {
                ctx.spawnSteps("worker", new byte[0]);
                ctx.spawnSteps("worker", new byte[0], SpawnOptions.id(input));
            });
        }
    }

    public static final class RefusingRelay extends Op {
        public RefusingRelay() {
            super("refusing_op", c -> c.input(String.class, "in").into(RefusingTask.class));
        }
    }

    /** Constructs at definition, then refuses once armed, as a resource gone by start time would. */
    @Tasklet.Spec(name = "refusing_task")
    public static final class RefusingTask extends Task<Void> {
        public RefusingTask() {
            super((input, ctx) -> {});
            if (REFUSE.get()) {
                throw new IllegalStateException(REFUSAL);
            }
        }
    }
}
