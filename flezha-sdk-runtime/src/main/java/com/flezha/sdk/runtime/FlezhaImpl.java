package com.flezha.sdk.runtime;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.Logger;
import com.flezha.sdk.internal.RunnerHandle;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import com.flezha.sdk.runtime.config.FlezhaEnv;
import com.flezha.sdk.runtime.core.ActivationDispatcher;
import com.flezha.sdk.runtime.core.CoreSpawnRequester;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.tasklet.SpawnRequests;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Runtime implementation of the internal {@link RunnerHandle} SPI the public
 * {@code FlezhaRunner} holds, resolved via reflection from
 * {@code FlezhaRunner.start()}. Hosts the poll core: declares every
 * registered class as {@code HostClassDecl} JSON, starts the core's runner
 * loop, and brings the threads the core's activations run on (see
 * {@link PollHost}). The core owns the wire — transport, framing, reconnect,
 * retry ladders, durability records — so this class owns only declaration and
 * lifecycle.
 */
public final class FlezhaImpl implements RunnerHandle {

    private static final Logger LOG = Flezha.log();

    private final String namespace;
    private final long core;
    private final ActivationDispatcher dispatcher;
    private final PollHost pollHost;
    private final Set<String> declared;
    private final Runnable onTerminated;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FlezhaImpl(
            String namespace,
            long core,
            ActivationDispatcher dispatcher,
            int fetchers,
            int fetcherMax,
            Set<String> declared,
            Runnable onTerminated) {
        this.namespace = namespace;
        this.core = core;
        this.dispatcher = dispatcher;
        this.declared = declared;
        this.onTerminated = onTerminated;
        this.pollHost = new PollHost(
                PollHost.of(core), dispatcher::dispatch, fetchers, fetcherMax, terminationLatch::countDown);
    }

    /**
     * Entry point invoked via reflection by {@code FlezhaRunner.start()}.
     * {@code declaredFactoryIds} supplies the runner's declared factory set;
     * {@code onTerminated} is the api layer's release hook for the JVM-wide
     * runner slot, run exactly once when {@link #close()} completes.
     */
    @Nonnull
    public static RunnerHandle start(
            @Nonnull String namespace,
            @Nonnull Supplier<List<String>> declaredFactoryIds,
            @Nonnull Runnable onTerminated) {
        FlezhaEnv.ResolvedConfig config = FlezhaEnv.resolveFromEnv();

        long core = FlzCore.newContext();
        Set<String> declared = ConcurrentHashMap.newKeySet();
        try {
            for (String factoryId : declaredFactoryIds.get()) {
                FlzCore.declareClass(core, HostClassDeclaration.forFactory(factoryId));
                declared.add(factoryId);
            }
            FlzCore.start(core, namespace);
        } catch (RuntimeException | Error e) {
            FlzCore.contextFree(core);
            throw e;
        }

        ActivationDispatcher dispatcher =
                new ActivationDispatcher((token, item, payload) -> FlzCore.part(core, token, item, payload));
        SpawnRequests.install(new CoreSpawnRequester(core));

        FlezhaImpl handle = new FlezhaImpl(
                namespace, core, dispatcher, config.fetcherCount(), config.fetcherMax(), declared, onTerminated);
        handle.pollHost.start();

        // SIGTERM (Kubernetes pre-stop) triggers the graceful teardown even
        // when the user never calls close(). Idempotent with close().
        Runtime.getRuntime().addShutdownHook(new Thread(handle::close, "flezha-graceful-shutdown"));

        LOG.info(
                "Flezha runner started on namespace `{}` with {} fetcher(s), supervisor ceiling {};"
                        + " activations run on virtual threads",
                namespace,
                handle.pollHost.fetcherCount(),
                handle.pollHost.maxFetcherCount());
        return handle;
    }

    /**
     * The runner's namespace. The core owns the controller-assigned wire
     * runner id (it rotates across the reconnects the core handles
     * internally), so the namespace is the stable host-side identity.
     */
    @Override
    @Nonnull
    public String runnerId() {
        return namespace;
    }

    @Override
    @Nonnull
    public Map<String, ?> operators() {
        return dispatcher.instances();
    }

    @Override
    public void removeType(@Nonnull String factoryId) {
        long live = dispatcher.instancesOf(factoryId);
        if (live > 0) {
            throw FlezhaException.of(
                    "runtime.operator_type_in_use", Map.of("factory_id", factoryId, "live_instances", live));
        }
        if (UnboundedOperatorClassRegistry.unregisterClass(factoryId) == null) {
            BoundedOperatorClassRegistry.unregisterClass(factoryId);
        }
        declared.remove(factoryId);
    }

    @Override
    @Nonnull
    public String adminCall(String verb, String args) {
        return FlzCore.adminCall(core, verb, args);
    }

    /**
     * Post-start define/remove resync. New ids are declared to the core;
     * removals have no ABI surface (the core keeps the declaration until the
     * context ends) — a known limitation, logged and otherwise a no-op.
     */
    @Override
    public void syncFactories(@Nonnull List<String> factoryIds) {
        for (String factoryId : factoryIds) {
            if (declared.contains(factoryId)) {
                continue;
            }
            if (UnboundedOperatorClassRegistry.getTopology(factoryId) == null
                    && BoundedOperatorClassRegistry.getTopology(factoryId) == null) {
                LOG.warn("syncFactories: `{}` is not in the class registries; skipping", factoryId);
                continue;
            }
            try {
                FlzCore.declareClass(core, HostClassDeclaration.forFactory(factoryId));
                declared.add(factoryId);
            } catch (RuntimeException e) {
                LOG.error("declaring `{}` post-start failed: {}", factoryId, e.getMessage());
            }
        }
        for (String known : declared) {
            if (!factoryIds.contains(known)) {
                LOG.debug("syncFactories: removal of `{}` has no core surface; declaration stays until close", known);
            }
        }
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        terminationLatch.await();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SpawnRequests.uninstall();
        FlzCore.shutdown(core);
        // Drains the in-flight activations too — each still owes the core a
        // `complete` on this context, which the free below invalidates.
        pollHost.close();
        FlzCore.contextFree(core);
        // Release the JVM-wide runner slot before waking waiters, so a thread
        // unblocked from awaitTermination() can immediately start a new runner.
        onTerminated.run();
        terminationLatch.countDown();
    }
}
