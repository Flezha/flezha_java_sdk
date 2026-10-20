package com.flezha.sdk.state;

/**
 * One recorded mutation of a cell's state, the unit that makes state durable.
 *
 * <p>A change is passed to {@code ctx.change(...)}, which calls {@link #applyTo} on the cell's
 * state and records the change. The runtime rebuilds the state after a restart by calling
 * {@link #applyTo} again for every recorded change, which is why a change must carry everything
 * needed to reapply it later:
 *
 * <pre>{@code
 * public record Incremented(long atMs) implements Change<Counter> {
 *     public void applyTo(Counter counter) {
 *         counter.count++;
 *         counter.lastMs = atMs;
 *     }
 * }
 * }</pre>
 *
 * <p>The timestamp is a component rather than something read during replay, when the original
 * value would no longer be available. Implementations are typically records nested in the tasklet
 * or its state class, where the SDK finds them for encoding.
 *
 * @param <S> the state the change applies to
 */
public interface Change<S> {

    /**
     * Applies this change to {@code state}. This is the only place a state field is written.
     *
     * <p>It runs when the change is recorded and again for every recorded change when a cell is
     * rebuilt, so it must apply the change and do nothing else: no emitting, no clock reads, no
     * external calls.
     *
     * @param state the cell's current state, mutated in place
     */
    void applyTo(S state);
}
