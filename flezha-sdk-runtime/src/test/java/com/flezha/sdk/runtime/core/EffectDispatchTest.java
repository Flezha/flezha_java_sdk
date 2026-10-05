package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ctx.effect}: what the host hands the core's effect entries and does with their answers.
 * Identities, keeping and the wait are the core's, so what is asserted here is the host's half: the
 * activation's token and the item's index, which answer runs the function, the encoding kept, the
 * value returned, the release, and which refusals are retried.
 */
class EffectDispatchTest extends DispatchTestBase {

    static final AtomicReference<byte[]> BEGIN = new AtomicReference<>();
    static final AtomicReference<byte[]> FIRST = new AtomicReference<>();
    static final AtomicReference<String> REFUSE_BEGIN = new AtomicReference<>();
    static final AtomicReference<String> REFUSE_END = new AtomicReference<>();

    static final IllegalStateException DOWN = new IllegalStateException("the model is down");

    @BeforeEach
    void recordEffects() {
        BEGIN.set(new byte[] {CoreCalls.EFFECT_RUN});
        FIRST.set(null);
        REFUSE_BEGIN.set(null);
        REFUSE_END.set(null);
        RelayClassRegistry.registerClass(EffectRelay.class);
        dispatcher = new ActivationDispatcher(new CoreCalls() {
            @Override
            public void part(long token, int item, @Nonnull byte[] payload) {}

            @Override
            @Nonnull
            public byte[] effectBegin(long token, int item, @Nonnull String name) {
                RECORDED.add("begin " + token + "/" + item + "/" + name);
                refuse(REFUSE_BEGIN.get(), name);
                return BEGIN.get();
            }

            @Override
            @Nullable
            public byte[] effectEnd(long token, int item, @Nonnull byte[] payload) {
                RECORDED.add("end " + token + "/" + item + "/" + new String(payload, StandardCharsets.UTF_8));
                refuse(REFUSE_END.get(), "model");
                return FIRST.get();
            }

            @Override
            public void effectRelease(long token, int item) {
                RECORDED.add("release " + token + "/" + item);
            }
        });
    }

    @Test
    @DisplayName("each item's effect reaches the core under the activation's token and the item's index")
    void anEffectCarriesTheTokenAndTheItemIndex() {
        ActivationOutcome out = outcome(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1), input(0, "b", 2)));

        assertThat(RECORDED)
                .containsExactly("begin 1/0/model", "ran a", "end 1/0/a", "begin 1/1/model", "ran b", "end 1/1/b");
        assertThat(((ActivationOutcome.Work) out).effects())
                .extracting(effect -> answer(effect))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("a kept result is returned and the function does not run")
    void aKeptResultIsReturnedWithoutRunningTheFunction() {
        BEGIN.set(begun(CoreCalls.EFFECT_KEPT, "kept earlier"));

        ItemEffect effect = soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)));

        assertThat(answer(effect)).isEqualTo("kept earlier");
        assertThat(RECORDED).containsExactly("begin 1/0/model");
    }

    @Test
    @DisplayName("a duplicate result resolves to the first")
    void aDuplicateResultResolvesToTheFirst() {
        FIRST.set("the first".getBytes(StandardCharsets.UTF_8));

        ItemEffect effect = soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)));

        assertThat(answer(effect)).isEqualTo("the first");
        assertThat(RECORDED).containsExactly("begin 1/0/model", "ran a", "end 1/0/a");
    }

    @Test
    @DisplayName("a call with nothing to keep runs the function, releases and keeps nothing")
    void aCallWithNothingToKeepReleases() {
        BEGIN.set(new byte[] {CoreCalls.EFFECT_UNRECORDED});

        ItemEffect effect = soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)));

        assertThat(answer(effect)).isEqualTo("a");
        assertThat(RECORDED).containsExactly("begin 1/0/model", "ran a", "release 1/0");
    }

    @Test
    @DisplayName("a failing function keeps nothing")
    void aFailingFunctionKeepsNothing() {
        ItemEffect effect = soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "fail", 1)));

        assertThat(effect.verdict()).isEqualTo(new Verdict.RetryableError(DOWN.toString()));
        assertThat(RECORDED).containsExactly("begin 1/0/model", "ran fail", "release 1/0");
    }

    @Test
    @DisplayName("a refusal is non-retryable, but for effect.not_recorded")
    void aRefusalIsNonRetryableButForNotRecorded() {
        REFUSE_BEGIN.set("effect.bad_name");
        assertThat(soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)))
                        .verdict())
                .isInstanceOfSatisfying(Verdict.NonRetryableError.class, v -> assertThat(v.message())
                        .contains("effect.bad_name"));
        assertThat(RECORDED).as("a refused call runs nothing").containsExactly("begin 1/0/model");

        REFUSE_BEGIN.set(null);
        REFUSE_END.set("effect.too_large");
        assertThat(soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)))
                        .verdict())
                .isInstanceOf(Verdict.NonRetryableError.class);

        REFUSE_END.set("effect.not_recorded");
        assertThat(soleEffect(work("effect_op", 0, Optional.empty(), 0, input(0, "a", 1)))
                        .verdict())
                .isInstanceOfSatisfying(Verdict.RetryableError.class, v -> assertThat(v.message())
                        .contains("effect.not_recorded"));
    }

    @Test
    @DisplayName("with no core the function runs each time and its result returns as it reads back")
    void withNoCoreTheFunctionRunsEachTime() {
        dispatcher = new ActivationDispatcher();
        RelayClassRegistry.registerClass(QuoteRelay.class);

        soleEffect(work("effect_quote_op", 0, Optional.empty(), 0, input(0, "EURUSD", 1)));
        soleEffect(work("effect_quote_op", 0, Optional.empty(), 0, input(0, "EURUSD", 2)));

        assertThat(RECORDED).containsExactly("ran EURUSD", "same EURUSD", "ran EURUSD", "same EURUSD");
    }

    public static final class EffectRelay extends Op {
        public EffectRelay() {
            super(
                    "effect_op",
                    c -> c.input(String.class, "in").into(EffectTask.class).output("answer"));
        }
    }

    /** Asks the "model" for its input, which answers it back or fails on {@code fail}. */
    @Tasklet.Spec(name = "effect_task")
    public static final class EffectTask extends Task<String> {
        public EffectTask() {
            super((input, ctx) -> ctx.emit(ctx.effect("model", String.class, () -> {
                RECORDED.add("ran " + input);
                if (input.equals("fail")) {
                    throw DOWN;
                }
                return input;
            })));
        }
    }

    public record Quote(String pair, int pips) {}

    public static final class QuoteRelay extends Op {
        public QuoteRelay() {
            super("effect_quote_op", c -> c.input(String.class, "in").into(QuoteTask.class));
        }
    }

    /** Records whether the typed result came back equal to, and not the same object as, its own. */
    @Tasklet.Spec(name = "effect_quote_task")
    public static final class QuoteTask extends Task<String> {
        public QuoteTask() {
            super((input, ctx) -> {
                Quote own = new Quote(input, 2);
                Quote read = ctx.effect("quote", Quote.class, () -> {
                    RECORDED.add("ran " + input);
                    return own;
                });
                RECORDED.add((read.equals(own) && read != own ? "same " : "changed ") + read.pair());
            });
        }
    }

    private static byte[] begun(byte status, String result) {
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        byte[] begun = new byte[bytes.length + 1];
        begun[0] = status;
        System.arraycopy(bytes, 0, begun, 1, bytes.length);
        return begun;
    }

    private static void refuse(@Nullable String code, String name) {
        if (code != null) {
            throw FlezhaException.of(code, Map.of("name", name, "reason", "refused", "bytes", 9, "limit", 8));
        }
    }

    private static String answer(ItemEffect effect) {
        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        return new String(effect.outputs().get(0).payload(), StandardCharsets.UTF_8);
    }
}
