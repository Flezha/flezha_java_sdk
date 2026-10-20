package com.flezha.sdk.runtime.core;

import java.util.List;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * One payload's prepare answers, in {@link ActivationKind.KeyRequest} payload
 * order. {@code values} carries, when {@code wantKeys}, every value each keyed
 * consumer's edge yields; {@code eventTime} the extracted event time when
 * {@code wantEventTime}.
 */
public record PrepareAnswer(@Nonnull List<Value> values, @Nonnull OptionalLong eventTime) {

    /**
     * One value for one keyed consumer, encoded as an edge value, and the Cells
     * it reaches: one key for a dynamic consumer, any number for a static fan-out.
     */
    public record Value(int consumer, @Nonnull List<String> keys, @Nonnull byte[] bytes) {}
}
