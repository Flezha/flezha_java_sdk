package com.flezha.sdk.internal;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SPI the runtime's {@code FlezhaImpl} implements and the public
 * {@code FlezhaRunner} holds — the ONE seam between the api module's concrete
 * runner handle and the runtime's connect/lifecycle machinery (resolved via
 * {@link RuntimeBridge}).
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls
 * this.
 */
public interface RunnerHandle {

    /** The controller-assigned per-session runner id (rotates across reconnects). */
    @Nonnull
    String runnerId();

    /** The ids of the relay instances bound on this runner, read from the core. */
    @Nonnull
    List<String> relays();

    /** Block until the runner shuts down. */
    void awaitTermination() throws InterruptedException;

    /** Graceful shutdown; idempotent; runs the api layer's slot-release hook. */
    void close();

    /**
     * Remove {@code factoryId}'s class from the runtime class registries —
     * unregister-first, then scan live instances; a live hit re-registers the
     * class and throws {@code runtime.relay_type_in_use}
     * (the transient unregistered window is benign: a failing
     * {@code AddRelay} is re-placed by convergence).
     */
    void removeType(String factoryId);

    /**
     * Declare the runner's FULL current factory set to the core after a
     * post-start define/remove. The set reaches the controller on the next
     * connect/reconnect init frame; the ABI has no entry point that pushes it
     * to a live controller.
     */
    void syncFactories(List<String> factoryIds);

    /**
     * Run one administration verb against the controller's published consumer
     * surface, as the identity this connection proved. Answers the published
     * function's own result as JSON.
     */
    @Nonnull
    String adminCall(String verb, String args);

    /**
     * Send {@code message} to the entry channel {@code channel} of
     * {@code relay}, through the controller or with {@code local} in
     * process. Answers the controller's verdict as JSON, or {@code null} as
     * JSON for a local send. A null {@code msgId} or {@code key} is none.
     */
    @Nonnull
    String send(
            String relay, String channel, byte[] message, @Nullable String msgId, @Nullable String key, boolean local);
}
