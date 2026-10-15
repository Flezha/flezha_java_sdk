package com.flezha.sdk.runtime.core;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.Logger;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * The pure host layer over the poll core: {@link #dispatch} turns one
 * {@link Activation} into one {@link ActivationResult} by running user code —
 * no native calls, which is what makes the whole typed layer unit-testable
 * without the core. The impurities are the api-side {@code SpawnRequests}
 * static, reached through {@code Ctx}'s default {@code spawnSteps}, and the
 * {@link CoreCalls} the runtime hands in, which {@code ctx.part} and {@code ctx.effect} reach.
 *
 * <p>The core guarantees per-Cell exclusivity — it never hands out a second
 * activation for the same Cell until the first completes — so there is no
 * Java-side Cell locking; the maps are merely concurrent-safe across
 * different Cells.
 */
public final class ActivationDispatcher {

    private static final Logger LOG = Flezha.log();

    private final ConcurrentHashMap<String, RelayInstance> instances = new ConcurrentHashMap<>();
    private final CoreCalls core;

    /**
     * A dispatcher with no core behind it: every part is dropped, as a run with no stream drops it,
     * and no effect is kept.
     */
    public ActivationDispatcher() {
        this((token, item, payload) -> {});
    }

    public ActivationDispatcher(@Nonnull CoreCalls core) {
        this.core = core;
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
            // Keyed by relay id, but the FACTORY the activation names is what
            // decides which class runs: a relay id rebound to a different
            // factory — a deploy putting the next version of a class behind the
            // same id — must not keep running the class we materialized first.
            RelayInstance instance = instances.compute(
                    activation.target().relay(),
                    (id, existing) -> existing != null && existing.factoryId().equals(activation.factory())
                            ? existing
                            : RelayInstance.materialize(id, activation.factory(), gone -> instances.remove(id, gone)));
            return new ActivationResult(activation.token(), instance.dispatch(activation, core));
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            LOG.error(
                    "activation dispatch failed for `{}`: {}",
                    activation.target().relay(),
                    message);
            return new ActivationResult(activation.token(), new ActivationOutcome.Failed(new WorkError(message, true)));
        }
    }
}
