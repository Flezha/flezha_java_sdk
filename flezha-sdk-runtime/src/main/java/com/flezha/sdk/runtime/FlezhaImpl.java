package com.flezha.sdk.runtime;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.Logger;
import com.flezha.sdk.control.Control;
import com.flezha.sdk.internal.RunnerHandle;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.core.ActivationDispatcher;
import com.flezha.sdk.runtime.core.CoreCalls;
import com.flezha.sdk.runtime.core.CoreSpawnRequester;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.tasklet.SpawnRequests;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    private final PollHost pollHost;
    private final Set<String> declared;
    private final Runnable onTerminated;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FlezhaImpl(
            String namespace, long core, ActivationDispatcher dispatcher, Set<String> declared, Runnable onTerminated) {
        this.namespace = namespace;
        this.core = core;
        this.declared = declared;
        this.onTerminated = onTerminated;
        this.pollHost = new PollHost(PollHost.of(core), dispatcher::dispatch, terminationLatch::countDown);
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
        long core = PollHost.newContext();
        Set<String> declared = ConcurrentHashMap.newKeySet();
        try {
            for (String factoryId : declaredFactoryIds.get()) {
                FlzCore.declareClass(core, HostClassDeclaration.forFactory(factoryId));
                declared.add(factoryId);
            }
            handSignalsToCore();
            FlzCore.start(core, namespace);
        } catch (RuntimeException | Error e) {
            // The core holds what it said while connecting until a take; no
            // supervisor is left to take it.
            PollHost.logNotices(FlzCore.takeNotices());
            FlzCore.contextFree(core);
            throw e;
        }

        ActivationDispatcher dispatcher = new ActivationDispatcher(CoreCalls.of(core));
        SpawnRequests.install(new CoreSpawnRequester(core));

        FlezhaImpl handle = new FlezhaImpl(namespace, core, dispatcher, declared, onTerminated);
        handle.pollHost.start();

        // An exit the application asks for still drains. Idempotent with
        // close() and with the core's own drain on SIGTERM.
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
    public List<String> relays() {
        List<String> ids = new ArrayList<>();
        Control.read("relays", FlzCore.relays(core)).forEach(id -> ids.add(id.asText()));
        return ids;
    }

    @Override
    public void removeType(@Nonnull String factoryId) {
        FlzCore.undeclareClass(core, factoryId);
        RelayClassRegistry.unregisterClass(factoryId);
        declared.remove(factoryId);
    }

    @Override
    @Nonnull
    public String adminCall(String verb, String args) {
        return FlzCore.adminCall(core, verb, args);
    }

    @Override
    @Nonnull
    public String send(
            String relay, String channel, byte[] message, @Nullable String msgId, @Nullable String key, boolean local) {
        return FlzCore.sendMessage(
                core, relay, channel, message, msgId == null ? "" : msgId, key == null ? "" : key, local);
    }

    /**
     * Post-start define resync: each new id is declared to the core, which
     * sends the controller the full set. A removal went through
     * {@link #removeType}.
     */
    @Override
    public void syncFactories(@Nonnull List<String> factoryIds) {
        for (String factoryId : factoryIds) {
            if (declared.contains(factoryId)) {
                continue;
            }
            if (RelayClassRegistry.getTopology(factoryId) == null) {
                LOG.warn("syncFactories: `{}` is not in the class registry; skipping", factoryId);
                continue;
            }
            try {
                FlzCore.declareClass(core, HostClassDeclaration.forFactory(factoryId));
                declared.add(factoryId);
            } catch (RuntimeException e) {
                LOG.error("declaring `{}` post-start failed: {}", factoryId, e.getMessage());
            }
        }
    }

    /**
     * Leave SIGTERM and SIGINT to the core's handler, as the Ruby and Node
     * SDKs do. The core's handler runs the one installed before it, and the
     * JVM's would end the process with 143 and race the core's drain; a
     * handler the application installed is kept. Reached by reflection,
     * because {@code sun.misc.Signal} is the JDK's one signal API and naming it
     * is a compiler warning.
     */
    static void handSignalsToCore() {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            java.lang.reflect.Method handle = signalClass.getMethod("handle", signalClass, handlerClass);
            Object handedOver = java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.getClassLoader(),
                    new Class<?>[] {handlerClass},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "handed to the flezha core";
                        default -> null;
                    });
            Object systemDefault = handlerClass.getField("SIG_DFL").get(null);
            for (String name : List.of("TERM", "INT")) {
                Object signal = signalClass.getConstructor(String.class).newInstance(name);
                Object previous = handle.invoke(null, signal, handedOver);
                // The JVM's own handler comes back wrapped; an application's as it was set.
                boolean jvms = previous.getClass().getName().equals("sun.misc.Signal$SunMiscHandler");
                if (previous != systemDefault && !jvms) {
                    handle.invoke(null, signal, previous);
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // -Xrs: the JVM handles neither signal, so there is nothing to hand over.
            LOG.debug("the JVM keeps the termination signals: {}", e.getCause().getMessage());
        } catch (ReflectiveOperationException e) {
            LOG.warn("could not hand SIGTERM and SIGINT to the core ({}); the JVM's handling stays", e.toString());
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
