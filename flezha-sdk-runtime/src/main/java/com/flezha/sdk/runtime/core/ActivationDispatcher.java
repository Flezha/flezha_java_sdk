package com.flezha.sdk.runtime.core;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * The pure host layer over the poll core: {@link #dispatch} turns one
 * {@link Activation} into one {@link ActivationResult} by running user code —
 * no native calls, which is what makes the whole typed layer unit-testable
 * without the core. The impurities are the api-side {@code SpawnRequests}
 * static, reached through {@code Ctx}'s default {@code spawnBounded}, and the
 * {@link Parts} the runtime hands in, which {@code ctx.part} reaches.
 *
 * <p>The core guarantees per-Cell exclusivity — it never hands out a second
 * activation for the same Cell until the first completes — so there is no
 * Java-side Cell locking; the maps are merely concurrent-safe across
 * different Cells.
 */
public final class ActivationDispatcher {

    private static final Logger LOG = Flezha.log();

    /** The core's part entry: one part of work item {@code item} of the activation {@code token}. */
    @FunctionalInterface
    public interface Parts {
        void send(long token, int item, @Nonnull byte[] payload);
    }

    private final ConcurrentHashMap<String, OperatorInstance> instances = new ConcurrentHashMap<>();
    private final Parts parts;

    /** A dispatcher with no core behind it: every part is dropped, as a run with no stream drops it. */
    public ActivationDispatcher() {
        this((token, item, payload) -> {});
    }

    public ActivationDispatcher(@Nonnull Parts parts) {
        this.parts = parts;
    }

    /** Live operator instances keyed by operator id — the {@code operators()} view. */
    @Nonnull
    public Map<String, OperatorInstance> instances() {
        return instances;
    }

    /** How many live instances belong to {@code factoryId}. */
    public long instancesOf(@Nonnull String factoryId) {
        return instances.values().stream()
                .filter(i -> factoryId.equals(i.factoryId()))
                .count();
    }

    @Nonnull
    public ActivationResult dispatch(@Nonnull Activation activation) {
        try {
            // Keyed by operator id, but the FACTORY the activation names is what
            // decides which class runs: an operator id rebound to a different
            // factory — a deploy putting the next version of a class behind the
            // same id — must not keep running the class we materialized first.
            OperatorInstance instance = instances.compute(
                    activation.target().operator(),
                    (id, existing) -> existing != null && existing.factoryId().equals(activation.factory())
                            ? existing
                            : OperatorInstance.materialize(id, activation.factory()));
            return new ActivationResult(activation.token(), instance.dispatch(activation, parts));
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            LOG.error(
                    "activation dispatch failed for `{}`: {}",
                    activation.target().operator(),
                    message);
            return new ActivationResult(activation.token(), new ActivationOutcome.Failed(new WorkError(message, true)));
        }
    }
}
