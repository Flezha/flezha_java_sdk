package com.flezha.sdk.state;

import java.util.Optional;

/**
 * One recorded mutation of a cell's state, the unit that makes state durable.
 *
 * <p>A change is passed to {@link StateHandle#apply(Change)} and applied to the state by the
 * tasklet's {@code applyChange}. The runtime records it, and rebuilds the state after a restart
 * by applying the recorded changes again, which is why a change must carry everything needed to
 * reapply it later:
 *
 * <pre>{@code
 * public record Increment(long atMs) implements Change {}
 * }</pre>
 *
 * <p>The timestamp is a component rather than something read during replay, when the original value
 * would no longer be available. Implementations are typically records: small, immutable and
 * Kryo-serialisable. A tasklet with several kinds of mutation declares one type per kind,
 * commonly as a sealed hierarchy:
 *
 * <pre>{@code
 * public sealed interface AccountChange extends Change {
 *     record Deposited(long amount) implements AccountChange {}
 *     record Withdrawn(long amount) implements AccountChange {}
 * }
 * }</pre>
 *
 * <p>{@link com.flezha.sdk.authoring.State @State} derives exactly this shape from a state
 * class, so a derived change and a hand-written one read the same at every call site.
 *
 * <p>A tasklet that persists nothing declares {@link NoChange} instead, which removes the change
 * machinery entirely.
 */
public interface Change {

    /**
     * An optional key that lets this change collapse with others carrying the same key. By default
     * there is none, and every change is recorded as it was applied.
     *
     * <p>Returning a key means only the latest change with that key survives a flush window. That
     * suits a "current value of X" update, where the intermediate values carry no information. It
     * is wrong for an additive delta such as an increment, where collapsing discards every value
     * but the last and the total is silently short.
     */
    default Optional<CoalesceKey> coalesceKey() {
        return Optional.empty();
    }
}
