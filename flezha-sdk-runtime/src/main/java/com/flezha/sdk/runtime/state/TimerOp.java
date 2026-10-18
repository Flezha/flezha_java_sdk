package com.flezha.sdk.runtime.state;

import com.flezha.sdk.state.TimerKind;
import javax.annotation.Nonnull;

/**
 * One durable mutation of a Cell's pending-timer set.
 *
 * <p>Field order is the wire contract — postcard is field-order sensitive, and
 * this must match the Rust SDK's {@code TimerOp} and the controller's
 * {@code Flezha.State.Wire} decoder exactly. Pinned by {@code ActivationCodecTest}
 * through the result rows of {@code test/fixtures/postcard_bodies.txt}.
 *
 * <p>Ops ride the WAL inside the effect record of an {@code ItemEffect}, which the
 * controller stores verbatim, so a firing's effects and the removal of the
 * timer that produced them land in ONE record.
 */
public record TimerOp(@Nonnull TimerKind kind, long scheduledAt, boolean register) {

    @Nonnull
    public static TimerOp register(@Nonnull TimerKind kind, long scheduledAt) {
        return new TimerOp(kind, scheduledAt, true);
    }

    @Nonnull
    public static TimerOp cancel(@Nonnull TimerKind kind, long scheduledAt) {
        return new TimerOp(kind, scheduledAt, false);
    }

    public static void encode(@Nonnull Postcard.Writer w, @Nonnull TimerOp op) {
        w.varint(op.kind().ordinal()).varint(op.scheduledAt()).bool(op.register());
    }
}
