package com.flezha.sdk.runtime.core;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The core's entries an invocation calls while it runs, for work item {@code item} of the
 * activation {@code token}: a part, and the begin, end and release of a {@code ctx.effect}.
 * {@link #of} binds them to a native context. The defaults are a run with no core behind it, which
 * keeps no effect, so its function runs each time.
 */
@FunctionalInterface
public interface CoreCalls {

    byte EFFECT_RUN = 0;
    byte EFFECT_KEPT = 1;
    byte EFFECT_UNRECORDED = 2;

    void part(long token, int item, @Nonnull byte[] payload);

    /** The status byte, then for {@link #EFFECT_KEPT} the kept result. */
    @Nonnull
    default byte[] effectBegin(long token, int item, @Nonnull String name) {
        return new byte[] {EFFECT_UNRECORDED};
    }

    /** {@code null} keeps {@code payload}; otherwise the result kept first. */
    @Nullable
    default byte[] effectEnd(long token, int item, @Nonnull byte[] payload) {
        return null;
    }

    default void effectRelease(long token, int item) {}

    /** The native context's entries. */
    @Nonnull
    static CoreCalls of(long ctx) {
        return new CoreCalls() {
            @Override
            public void part(long token, int item, @Nonnull byte[] payload) {
                FlzCore.part(ctx, token, item, payload);
            }

            @Override
            @Nonnull
            public byte[] effectBegin(long token, int item, @Nonnull String name) {
                return FlzCore.effectBegin(ctx, token, item, name);
            }

            @Override
            @Nullable
            public byte[] effectEnd(long token, int item, @Nonnull byte[] payload) {
                return FlzCore.effectEnd(ctx, token, item, payload);
            }

            @Override
            public void effectRelease(long token, int item) {
                FlzCore.effectRelease(ctx, token, item);
            }
        };
    }

    /** {@code ctx.effect} over these entries; the result as it reads back, on every path. */
    default <T> T effect(long token, int item, String name, Class<T> type, Supplier<T> fn) throws TaskletException {
        byte[] begun = refusable(() -> effectBegin(token, item, name));
        if (begun[0] == EFFECT_KEPT) {
            return PlainPayload.decode(Arrays.copyOfRange(begun, 1, begun.length), type);
        }
        byte[] encoded;
        try {
            encoded = PlainPayload.encode(fn.get());
        } catch (Throwable t) {
            effectRelease(token, item);
            throw t;
        }
        if (begun[0] == EFFECT_UNRECORDED) {
            effectRelease(token, item);
            return PlainPayload.decode(encoded, type);
        }
        byte[] first = refusable(() -> effectEnd(token, item, encoded));
        return PlainPayload.decode(first == null ? encoded : first, type);
    }

    /**
     * An {@code effect.*} refusal as non-retryable, but for {@code effect.not_recorded}: a call that
     * could not be kept is the one worth running again.
     */
    private static byte[] refusable(Supplier<byte[]> call) throws TaskletException {
        try {
            return call.get();
        } catch (FlezhaException e) {
            if (e.code().startsWith("effect.") && !e.code().equals("effect.not_recorded")) {
                throw new TaskletException.NonRetryable(e.getMessage(), e);
            }
            throw e;
        }
    }
}
