package com.flezha.sdk.runtime;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.internal.RunnerHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Test double for the runtime entry point, resolved reflectively by
 * {@code FlezhaRunner.start()} (the real one lives in
 * {@code flezha-sdk-runtime} and needs native transport + a controller).
 * Implements the {@code RunnerHandle} SPI with the real lifecycle contract:
 * {@code close()} is idempotent, runs the api layer's {@code onTerminated}
 * release hook, then wakes every thread blocked in
 * {@code awaitTermination()}. {@code syncFactories} records every shipped set
 * ({@link #SYNCED_SETS}); {@link #REMOVAL_REFUSAL} stands in for the core's
 * refusal of a removal, so the api layer's handling of it needs no core.
 */
public final class FlezhaImpl implements RunnerHandle {

    /** Successful start() count — guard tests assert a rejected start never reaches the runtime. */
    public static final AtomicInteger STARTS = new AtomicInteger();

    /** What the core answers the next {@link #removeType(String)} with, when set. */
    public static volatile FlezhaException REMOVAL_REFUSAL;

    /** Every factory set {@link #syncFactories(List)} shipped, in call order. */
    public static final List<List<String>> SYNCED_SETS = new ArrayList<>();

    /** Every {@code (verb, args)} an administration call carried, in call order. */
    public static final List<List<String>> ADMIN_CALLS = new ArrayList<>();

    /** Every {@code (relay, channel, message, msgId, key, local)} a send carried, in call order. */
    public static final List<List<Object>> SENDS = new ArrayList<>();

    /** What {@link #send} answers. */
    public static volatile String SEND_ANSWER = "null";

    /** What {@link #adminCall(String, String)} answers. */
    public static volatile String ADMIN_ANSWER = "null";

    /** The declared-set supplier of the LAST started runner — handshake/reconnect resync assertions. */
    public static volatile Supplier<List<String>> LAST_DECLARED_SUPPLIER;

    private final String namespace;
    private final Runnable onTerminated;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FlezhaImpl(String namespace, Runnable onTerminated) {
        this.namespace = namespace;
        this.onTerminated = onTerminated;
    }

    @Nonnull
    public static RunnerHandle start(
            @Nonnull String namespace,
            @Nonnull Supplier<List<String>> declaredFactoryIds,
            @Nonnull Runnable onTerminated) {
        STARTS.incrementAndGet();
        LAST_DECLARED_SUPPLIER = declaredFactoryIds;
        return new FlezhaImpl(namespace, onTerminated);
    }

    /** Test hook: clear the fake state between tests. */
    public static void resetForTests() {
        REMOVAL_REFUSAL = null;
        synchronized (SYNCED_SETS) {
            SYNCED_SETS.clear();
        }
        LAST_DECLARED_SUPPLIER = null;
    }

    @Override
    @Nonnull
    public String runnerId() {
        return namespace;
    }

    @Override
    @Nonnull
    public List<String> relays() {
        return List.of();
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        terminationLatch.await();
    }

    @Override
    public void removeType(@Nonnull String factoryId) {
        if (REMOVAL_REFUSAL != null) {
            throw REMOVAL_REFUSAL;
        }
        com.flezha.sdk.runtime.relay.RelayClassRegistry.unregisterClass(factoryId);
    }

    @Override
    public void syncFactories(@Nonnull List<String> factoryIds) {
        synchronized (SYNCED_SETS) {
            SYNCED_SETS.add(List.copyOf(factoryIds));
        }
    }

    @Override
    @Nonnull
    public String adminCall(String verb, String args) {
        ADMIN_CALLS.add(List.of(verb, args));
        return ADMIN_ANSWER;
    }

    @Override
    @Nonnull
    public String send(String relay, String channel, byte[] message, String msgId, String key, boolean local) {
        SENDS.add(java.util.Arrays.asList(
                relay, channel, new String(message, java.nio.charset.StandardCharsets.UTF_8), msgId, key, local));
        return SEND_ANSWER;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        onTerminated.run();
        terminationLatch.countDown();
    }
}
