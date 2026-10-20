package com.flezha.sdk.runtime.core;

import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * One unit of work handed out by the poll core ({@code flz_core}'s
 * {@code Activation}). Targets exactly one Cell (or one not-yet-keyed edge
 * for {@link ActivationKind.KeyRequest}) and is exclusive: the core hands out
 * no second activation for the same Cell until this one's
 * {@link ActivationResult} arrives.
 *
 * <p>{@code factory} is the declared class's factory id, so the host can
 * resolve which registered class the instance belongs to.
 *
 * <p>{@code cellIdentity} rides the Cell's first activation since the host
 * last dropped or rebuilt it.
 *
 * <p>Postcard-encoded — field order is the wire contract, pinned
 * cross-language by {@code ActivationCodecTest}.
 */
public record Activation(
        long token,
        @Nonnull CellRef target,
        @Nonnull ActivationKind kind,
        long changeSeq,
        @Nonnull String factory,
        @Nonnull Optional<byte[]> cellIdentity) {}
