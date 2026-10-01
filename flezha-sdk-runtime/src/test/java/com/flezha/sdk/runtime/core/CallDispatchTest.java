package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.runtime.operator.OperatorClassRegistry;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An emit on a channel that ends in a call: each request the call chain makes leaves as a plugin
 * output keyed by the chain, and an outcome on the Response channel reaches the tasklet decoded.
 */
class CallDispatchTest extends DispatchTestBase {

    /** Channel ids: 0 {@code in}, 1 {@code ask}, 2 {@code ask.response}. */
    public static final class CallOp extends Op {
        public CallOp() {
            super("call_op", c -> c.input(String.class, "in")
                    .into(AskTask.class)
                    .named("ask")
                    .filter(value -> !value.startsWith("drop"))
                    .flatMap(String.class, value -> List.of(value, value + "!"))
                    .keyBy(value -> value.substring(0, value.indexOf(':')))
                    .call(CallTarget.factory("agent"), String.class, 500)
                    .map(
                            String.class,
                            response -> "answered " + response.request() + "=" + response.answer() + "@"
                                    + response.target())
                    .into(AskTask.class));
        }
    }

    @Tasklet.Spec(name = "ask_task")
    public static final class AskTask extends Task<String> {
        public AskTask() {
            super((input, ctx) -> ctx.emit(input));
        }
    }

    @Test
    @DisplayName("each request the call chain makes leaves as a plugin output keyed by the chain")
    void eachRequestLeavesAsAPluginOutputKeyedByTheChain() {
        OperatorClassRegistry.registerClass(CallOp.class);
        ItemEffect effect = soleEffect(work("call_op", 0, Optional.empty(), 1, input(0, "s1:hi", 1_000)));

        assertThat(effect.outputs()).hasSize(2);
        assertThat(effect.outputs()).allSatisfy(output -> {
            assertThat(output.target()).isEqualTo(new OutputTarget.Plugin("ask"));
            assertThat(output.key()).contains("s1");
        });
        assertThat(effect.outputs().stream()
                        .map(output -> new String(output.payload(), StandardCharsets.UTF_8))
                        .toList())
                .containsExactly("s1:hi", "s1:hi!");
    }

    @Test
    @DisplayName("a value the call chain filters out makes no request")
    void aValueTheCallChainFiltersOutMakesNoRequest() {
        OperatorClassRegistry.registerClass(CallOp.class);
        ItemEffect effect = soleEffect(work("call_op", 0, Optional.empty(), 1, input(0, "drop:x", 1_000)));

        assertThat(effect.outputs()).isEmpty();
    }

    @Test
    @DisplayName("an outcome on the Response channel reaches the tasklet as a decoded Response")
    void anOutcomeReachesTheTaskletDecoded() {
        OperatorClassRegistry.registerClass(CallOp.class);
        String envelope =
                "{\"call\":\"c\",\"target\":\"s1\",\"created\":true," + "\"request\":\"czE6aGk=\",\"value\":\"b2s=\"}";
        ItemEffect effect = soleEffect(work("call_op", 0, Optional.empty(), 1, input(2, envelope, 1_000)));

        assertThat(effect.outputs())
                .as("the tasklet emits the answer line, which the call chain sends on as its own requests")
                .hasSize(2)
                .first()
                .satisfies(output -> {
                    assertThat(new String(output.payload(), StandardCharsets.UTF_8))
                            .isEqualTo("answered s1:hi=ok@s1");
                    assertThat(output.key()).contains("answered s1");
                });
    }
}
