package com.flezha.sdk.runtime.core;

import javax.annotation.Nonnull;

/**
 * Per-item verdict inside an {@link ItemEffect}. Variant order is the
 * postcard enum discriminant.
 */
public sealed interface Verdict {

    /** Discriminant 0. */
    record Ok() implements Verdict {}

    /** Discriminant 1 — the item failed and may be re-attempted. */
    record RetryableError(@Nonnull String message) implements Verdict {}

    /** Discriminant 2 — the item failed and must not be re-attempted. */
    record NonRetryableError(@Nonnull String message) implements Verdict {}

    /**
     * Discriminant 3: the entry channel's decoder, or a transform on its edge,
     * rejected the payload before any tasklet ran. A flow operator parks the item as
     * a non-retryable error would; a steps operator parks the message under the entry
     * channel once the stage's ladder exhausts, and goes on waiting instead of
     * ending.
     */
    record Unreadable(@Nonnull String message) implements Verdict {}
}
