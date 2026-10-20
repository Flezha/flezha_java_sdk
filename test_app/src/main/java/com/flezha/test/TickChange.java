package com.flezha.test;

import com.flezha.sdk.state.Change;

/**
 * Per-tick Change carrying an interval bucket, timestamp, and delta, folded by
 * {@link CoalescingCounterRelay}. The Java SDK does not coalesce: every change
 * is recorded as it was applied.
 */
public record TickChange(int interval, long ts, long delta) implements Change<TickTasklet.State> {

    @Override
    public void applyTo(TickTasklet.State state) {
        state.last = delta;
    }
}
