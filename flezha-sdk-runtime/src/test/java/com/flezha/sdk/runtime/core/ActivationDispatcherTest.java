package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.tasklet.SpawnOptions;
import com.flezha.sdk.tasklet.SpawnRequests;
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
        UnboundedOperatorClassRegistry.registerClass(PoisonOperator.class);
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
    @DisplayName("a payload the entry decoder rejects is Unreadable; a tasklet throwing on a readable one is retryable")
    void unreadableEntryPayloadIsToldApartFromATaskletFailure() {
        UnboundedOperatorClassRegistry.registerClass(TypedEntryOperator.class);

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
        UnboundedOperatorClassRegistry.registerClass(ReplayProbeOperator.class);
        soleEffect(work("replay_probe", 0, Optional.empty(), 0, input(0, "x", 1, OptionalLong.empty(), true)));
        assertThat(RECORDED).containsExactly("replaying=true");
    }

    @Test
    @DisplayName("a spawn carries the activation's token and the item's replay flag to the core")
    void aSpawnCarriesTheTokenAndTheReplayFlag() {
        UnboundedOperatorClassRegistry.registerClass(SpawnProbeOperator.class);
        SpawnRequests.install(new SpawnRequests.Requester() {
            @Override
            public long request(
                    long token, String factoryId, String id, byte[] payload, String ifRunning, boolean replay) {
                RECORDED.add(token + "/" + factoryId + "/" + id + "/" + replay);
                return 1;
            }

            @Override
            public String result(long handle, long timeoutMs) {
                return null;
            }
        });
        try {
            for (boolean replay : new boolean[] {false, true}) {
                outcome(new Activation(
                        42,
                        new CellRef(OP, 0, Optional.empty()),
                        new ActivationKind.Work(List.of(input(0, "w1", 1, OptionalLong.empty(), replay))),
                        0,
                        "spawn_probe"));
            }
        } finally {
            SpawnRequests.uninstall();
        }
        assertThat(RECORDED)
                .containsExactly("42/worker//false", "42/worker/w1/false", "42/worker//true", "42/worker/w1/true");
    }

    @Test
    @DisplayName("a tasklet constructor that throws at materialization surfaces the author's error")
    void throwingTaskletConstructorAtMaterializationIsTheAuthorsError() {
        UnboundedOperatorClassRegistry.registerClass(RefusingOperator.class);
        REFUSE.set(true);

        assertThatThrownBy(() -> OperatorInstance.materialize(OP, "refusing_op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REFUSAL);
    }

    public static final class SpawnProbeOperator extends Op {
        public SpawnProbeOperator() {
            super("spawn_probe", c -> c.input(String.class, "in").into(SpawnProbeTask.class));
        }
    }

    /** Spawns once without an id and once under its input. */
    public static final class SpawnProbeTask extends Task<Void> {
        public SpawnProbeTask() {
            super("spawn_probe_task", (input, ctx) -> {
                ctx.spawnBounded("worker", new byte[0]);
                ctx.spawnBounded("worker", new byte[0], SpawnOptions.id(input));
            });
        }
    }

    public static final class RefusingOperator extends Op {
        public RefusingOperator() {
            super("refusing_op", c -> c.input(String.class, "in").into(RefusingTask.class));
        }
    }

    /** Constructs at definition, then refuses once armed, as a resource gone by start time would. */
    public static final class RefusingTask extends Task<Void> {
        public RefusingTask() {
            super("refusing_task", (input, ctx) -> {});
            if (REFUSE.get()) {
                throw new IllegalStateException(REFUSAL);
            }
        }
    }
}
