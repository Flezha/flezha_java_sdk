package com.flezha.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ctx.effect} in {@link TaskletTestHarness}: an applied input keeps an outside call's result,
 * {@code replayEmitsEqual} is served it without running the function, a replay that reaches outside
 * some other way fails naming the effect, and the refusals.
 */
class EffectHarnessTest {

    /** How often each outside call ran, by question. */
    static final Map<String, Integer> CALLS = new HashMap<>();

    /** The codes the tasklets under test caught, each with whether it came non-retryable. */
    static final List<String> REFUSALS = new ArrayList<>();

    @BeforeEach
    void reset() {
        CALLS.clear();
        REFUSALS.clear();
    }

    static String model(String question) {
        return question + "#" + CALLS.merge(question, 1, Integer::sum);
    }

    @Tasklet.Spec(name = "effect_asks")
    public static final class Asks implements Tasklet<String, Void, String> {
        @Override
        public void run(String question, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(ctx.effect("model", String.class, () -> model(question)));
            ctx.emit(ctx.effect("model", String.class, () -> model(question)));
            ctx.emit(ctx.effect("tool", String.class, () -> model("tool")));
        }
    }

    @Test
    @DisplayName("an applied input runs the function and a replay is served its result, by name and in order")
    void anAppliedInputRunsTheFunctionAndAReplayIsServedItsResult() {
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new Asks()).applyInputs(List.of("EURUSD", "GBPUSD"));

        assertThat(harness.recordedEmits())
                .containsExactly("EURUSD#1", "EURUSD#2", "tool#1", "GBPUSD#1", "GBPUSD#2", "tool#2");
        assertThat(harness.replayEmitsEqual(List.of("EURUSD", "GBPUSD"))).isEmpty();
        assertThat(CALLS).as("the replay ran no function").isEqualTo(Map.of("EURUSD", 2, "GBPUSD", 2, "tool", 2));
    }

    /** One more call each time it runs, so its replay asks for a result nothing kept. */
    @Tasklet.Spec(name = "effect_one_more")
    public static final class OneMore implements Tasklet<String, Void, String> {
        private int runs;

        @Override
        public void run(String question, Ctx<Void, String> ctx) throws TaskletException {
            runs++;
            for (int call = 0; call < runs; call++) {
                ctx.effect("model", String.class, () -> "same");
            }
        }
    }

    @Test
    @DisplayName("a replay that runs a function fails naming the effect")
    void aReplayThatRunsAFunctionFailsNamingTheEffect() {
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new OneMore()).applyInputs(List.of("q"));

        assertThat(harness.replayEmitsEqual(List.of("q")))
                .hasValueSatisfying(diagnostic -> assertThat(diagnostic).startsWith("effect `model` ran its function"));
    }

    /** Asks through {@code ctx.effect} live, and around it afterwards. */
    @Tasklet.Spec(name = "effect_around")
    public static final class Around implements Tasklet<String, Void, String> {
        private boolean live = true;

        @Override
        public void run(String question, Ctx<Void, String> ctx) throws TaskletException {
            if (live) {
                live = false;
                ctx.emit(ctx.effect("model", String.class, () -> model("around")));
            } else {
                ctx.emit(model("around"));
            }
        }
    }

    @Test
    @DisplayName("a call made around the effect on replay fails naming it")
    void aCallMadeAroundTheEffectOnReplayFailsNamingIt() {
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new Around()).applyInputs(List.of("q"));

        assertThat(harness.replayEmitsEqual(List.of("q"))).hasValueSatisfying(diagnostic -> assertThat(diagnostic)
                .startsWith("effect `model` kept a result")
                .contains("never asked for"));
    }

    /** Records each refusal: an empty or long name as given, or {@code nested}. */
    @Tasklet.Spec(name = "effect_refused")
    public static final class Refused implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) {
            try {
                if (input.equals("nested")) {
                    ctx.effect("outer", String.class, () -> {
                        try {
                            return ctx.effect("inner", String.class, () -> "ran");
                        } catch (TaskletException refused) {
                            REFUSALS.add(describe(refused));
                            return "outer";
                        }
                    });
                } else {
                    ctx.effect(input, String.class, () -> {
                        throw new AssertionError("a refused call runs nothing");
                    });
                }
            } catch (TaskletException refused) {
                REFUSALS.add(describe(refused));
            }
        }

        private static String describe(TaskletException refused) {
            String retry = refused instanceof TaskletException.NonRetryable ? "non-retryable " : "retryable ";
            return retry + ((FlezhaException) refused.getCause()).code();
        }
    }

    @Test
    @DisplayName("a bad name is refused, and an effect inside an effect, without a retry")
    void aBadNameAndANestedEffectAreRefused() {
        TaskletTestHarness.of(new Refused()).applyInputs(List.of("", "m".repeat(256), "nested"));

        assertThat(REFUSALS)
                .containsExactly(
                        "non-retryable effect.bad_name",
                        "non-retryable effect.bad_name",
                        "non-retryable effect.nested");
    }

    /** Fails its first call, and answers the second. */
    @Tasklet.Spec(name = "effect_fails")
    public static final class Fails implements Tasklet<String, Void, String> {
        static final IllegalStateException DOWN = new IllegalStateException("down");
        private int attempts;

        @Override
        public void run(String question, Ctx<Void, String> ctx) throws TaskletException {
            attempts++;
            ctx.emit(ctx.effect("model", String.class, () -> {
                if (attempts == 1) {
                    throw DOWN;
                }
                return "second";
            }));
        }
    }

    @Test
    @DisplayName("a failing function keeps nothing")
    void aFailingFunctionKeepsNothing() {
        TaskletTestHarness<String, Void, String> harness = TaskletTestHarness.of(new Fails());

        assertThatThrownBy(() -> harness.applyInputs(List.of("q"))).isSameAs(Fails.DOWN);
        assertThat(harness.applyInputs(List.of("q")).recordedEmits())
                .as("the failed call kept nothing, so the next one runs")
                .containsExactly("second");
    }
}
