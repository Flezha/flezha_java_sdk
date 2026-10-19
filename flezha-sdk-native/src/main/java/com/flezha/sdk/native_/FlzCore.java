package com.flezha.sdk.native_;

/**
 * Java surface of the poll-based SDK core ({@code flz_core_ffi}). The host
 * polls postcard-encoded Activations, runs user code, and completes with
 * postcard-encoded ActivationResults.
 *
 * <p>Buffer ownership: every {@code byte[]} returned is a Java-owned copy —
 * the native buffer is freed before the call returns; every {@code byte[]}
 * passed in is copied by the native side before the call returns.
 *
 * <p>Native method names are the short JNI form
 * ({@code Java_com_flezha_sdk_native_1_FlzCore_<method>}), matching the
 * exports in {@code flz_core_ffi/src/jni_glue.rs} 1:1. Failures throw
 * {@link com.flezha.sdk.FlezhaException}, built from the core's error document.
 */
public final class FlzCore {

    /** The activation schema version this shim was built against. */
    public static final int SCHEMA_VERSION = 4;

    static {
        NativeLibraryLoader.loadNativeLibrary("libflz_core_ffi");
    }

    private FlzCore() {}

    /** Create a context negotiated at {@link #SCHEMA_VERSION}. */
    public static long newContext() {
        return contextNew(SCHEMA_VERSION);
    }

    /** The activation schema version the loaded native core speaks. */
    public static native int schemaVersion();

    /**
     * Create the core context, negotiating the schema version. Throws on
     * mismatch, naming both versions. No controller connection is attempted.
     */
    public static native long contextNew(int shimSchemaVersion);

    /** Declare one host class from its JSON declaration. Throws the refusal. */
    public static native void declareClass(long ctx, byte[] json);

    /**
     * Undeclare one host class. After start the runner's coordinator refuses a
     * class with live instances ({@code runtime.relay_type_in_use}), and the
     * controller is sent the remaining set. Blocks for that round trip.
     */
    public static native void undeclareClass(long ctx, String factoryId);

    /**
     * Judge one host class declaration without registering it: the one seam
     * every SDK's builder submits its record to. Returns the DERIVED
     * declaration's JSON bytes — the consumers, channel kinds, output names and
     * acknowledgement granularity the core read off the record. A refused
     * declaration throws with the first refusal's code, and
     * {@code details.issues} lists every refusal as {@code {code, message,
     * details}}, so a caller reads which fired as data.
     */
    public static native byte[] checkClass(long ctx, byte[] json);

    /** Connect to the controller and start the runner loop. Throws on failure. */
    public static native void start(long ctx, String namespace);

    /**
     * Start the declared class as a local run: no controller, no socket. The
     * alternative to {@link #start}, not an addition to it — the host still
     * drives {@link #poll} / {@link #complete}, which is what runs the
     * tasklets. Throws, naming the capability, when the declaration needs a
     * controller.
     */
    public static native void localStart(long ctx, String factoryId, String relayId);

    /** Queue the output named {@code channel} for {@link #localNextOutput}. */
    public static native void localOnSink(long ctx, String channel);

    /**
     * Feed one value's wire bytes to a declared entry channel — the same
     * bytes a controller would forward. Blocks while the consumer's mailbox
     * is full.
     */
    public static native void localFeed(long ctx, String channel, byte[] payload);

    /**
     * Block up to {@code timeoutMs} for the next value the graph released on
     * a queued output. {@code null} = timeout; otherwise the postcard-encoded
     * Output bytes.
     */
    public static native byte[] localNextOutput(long ctx, long timeoutMs);

    /** Block until the graph is idle; throws when {@code timeoutMs} elapses first. */
    public static native void localRunUntilIdle(long ctx, long timeoutMs);

    /** End the local run. A context with none is a no-op. */
    public static native void localStop(long ctx);

    /**
     * Block up to {@code timeoutMs} for one activation. {@code null} = timeout;
     * a zero-length array = shutting down (an encoded activation is never
     * empty); otherwise the postcard-encoded Activation bytes.
     */
    public static native byte[] poll(long ctx, long timeoutMs);

    /**
     * Complete an activation with a postcard-encoded ActivationResult.
     * Accepted from any thread. Throws on a decode/apply refusal.
     */
    public static native void complete(long ctx, byte[] result);

    /** {@link #waitStarvation} saw work queued with no thread left to take it. */
    public static final int STARVATION_OBSERVED = 1;

    /** {@link #waitStarvation} reached its timeout with nothing owed. */
    public static final int STARVATION_TIMEOUT = 0;

    /** {@link #waitStarvation} returned because the core is shutting down. */
    public static final int STARVATION_SHUTTING_DOWN = 2;

    /**
     * Block up to {@code timeoutMs} until the core observes starvation —
     * runnable work with no thread inside {@link #poll} and the jobs-underway
     * cap not reached. Returns {@link #STARVATION_OBSERVED},
     * {@link #STARVATION_TIMEOUT} or {@link #STARVATION_SHUTTING_DOWN}.
     *
     * <p>The other half of the bring-your-own-thread contract: the core caps
     * how much runs, the host supplies threads, and this is how the host is
     * told it owes one. A host that ships a fixed worker count and never calls
     * it wedges with no recovery and no report when every worker is held.
     */
    public static native int waitStarvation(long ctx, long timeoutMs);

    /**
     * Abandon a polled activation by token: its untouched items are re-handed
     * and its cell released, exactly as if it had never been polled. No
     * retry-ladder attempt is consumed.
     *
     * <p>The third and last way to answer a handout. No unwind crosses the
     * native boundary, so a throwable that escapes the dispatch has no way to
     * reach {@link #complete}; without this call the activation's cell would
     * be retired for the life of the process (per-cell exclusivity is the
     * handout itself). Call it from a {@code finally}, never from a
     * hand-written return site.
     *
     * @throws RuntimeException if the token was never handed out or was
     *     already answered
     */
    public static native void abandon(long ctx, long token);

    /**
     * The ABI form of {@code ctx.spawnSteps}: request a steps instance
     * in the runner's own namespace from the polled activation {@code token},
     * returning the handle number at once. An empty {@code id} asks the
     * controller to generate one; {@code ifRunning} is the wire string
     * {@code "deliver"} or {@code "error"}; {@code replay} sends nothing and
     * resolves the handle as skipped; {@code offset} is the input-log offset
     * of the item the request runs under, or -1 for none. A refusal reaches
     * only {@link #spawnResult}. Throws for an unreadable argument or a token
     * that is not outstanding.
     */
    public static native long spawnRequest(
            long ctx,
            long token,
            String factoryId,
            String id,
            byte[] payload,
            String ifRunning,
            boolean replay,
            long offset);

    /**
     * Read a spawn handle, waiting up to {@code timeoutMs} (0 never waits):
     * {@code null} while pending, otherwise its answer as JSON,
     * {@code {"id","outcome"}}. A failed spawn throws with the controller's
     * code, or {@code spawn.timeout} / {@code spawn.unavailable}. Every read
     * of a resolved handle answers the same until the activation that made it
     * ends, which releases it; reading a released handle throws
     * {@code runtime.spawn_handle_released}.
     */
    public static native String spawnResult(long ctx, long handle, long timeoutMs);

    /**
     * The ABI form of {@code ctx.part}: send a part of the invocation of work item {@code item}
     * (its index in the polled activation {@code token}'s list) at once. The core numbers, stamps
     * and, when it cannot leave at once, drops it. Throws {@code part.no_output} when the tasklet's
     * output channel ends in no output, {@code part.outside_invocation} for a token no longer
     * outstanding or an item it does not hold.
     */
    public static native void part(long ctx, long token, int item, byte[] payload);

    /**
     * Begin {@code ctx.effect(name)} in work item {@code item} of the activation {@code token}. The
     * first byte is the status: 0 runs the function, 1 answers the kept result that follows, 2 runs
     * it with nothing to keep. Never waits. Throws {@code effect.bad_name}, {@code effect.nested} or
     * {@code effect.outside_invocation}.
     */
    public static native byte[] effectBegin(long ctx, long token, int item, String name);

    /**
     * Keep the encoded result of the function a status-0 begin ran, waiting for the controller:
     * {@code null} keeps this result, otherwise the result the controller kept first. Throws
     * {@code effect.too_large} or {@code effect.not_recorded}.
     */
    public static native byte[] effectEnd(long ctx, long token, int item, byte[] payload);

    /** End a begun effect without keeping anything. */
    public static native void effectRelease(long ctx, long token, int item);

    /**
     * The pending timers of the Cell the activation {@code token} runs, as the core keeps them:
     * {@code [kind, at, kind, at, ...]}, kind {@code 0} for event time and {@code 1} for processing
     * time. Throws {@code runtime.unknown_activation_token} for a token no longer outstanding.
     */
    public static native long[] pendingTimers(long ctx, long token);

    /**
     * Administer the namespace this connection proved: one entry for the whole
     * surface, because the verb is data. {@code verb} names a function of the
     * controller's published consumer surface ({@code "Relay.start"}) and
     * {@code args} is a JSON array of that call's arguments other than the
     * namespace, which the controller fills from the connection. Returns the
     * published function's own answer as JSON; throws on refusal, no control
     * plane, or timeout.
     *
     * <p>A further verb therefore costs a Java method and no JNI.
     */
    public static native String adminCall(long ctx, String verb, String args);

    /**
     * Send {@code message} to the entry channel {@code channel} of
     * {@code relay}: through the controller, answering its verdict as JSON,
     * or with {@code local} in process to a relay bound on this runner,
     * answering {@code null}. An empty {@code msgId} or {@code key} is none.
     * Blocks for the answer, or while a local consumer's mailbox is full.
     */
    public static native String sendMessage(
            long ctx, String relay, String channel, byte[] message, String msgId, String key, boolean local);

    /** The ids of the relay instances bound on this runner, as a JSON array. */
    public static native String relays(long ctx);

    /**
     * The core's monotonic clock in nanoseconds since an arbitrary process
     * origin: the one clock every host's {@code ctx} reads. Context-free.
     */
    public static native long monotonicNanos();

    /**
     * A knob's text parsed by the core's rule for {@code kind} ({@code positive}, {@code ms},
     * {@code int} or {@code bool}), as its decimal text; null when it is no value of that kind.
     * Context-free.
     */
    public static native String knob(String kind, String raw);

    /**
     * The core's notices held since the last take, as a JSON array of
     * {@code {"level", "target", "text"}}, {@code level} one of {@code info},
     * {@code warn}, {@code error} or {@code always} (the local-mode banner).
     * The first take starts the holding: from then on a notice waits for the
     * next take instead of going to standard error. Context-free.
     */
    public static native String takeNotices();

    /** Wake every blocked poller and stop the runner loop. */
    public static native void shutdown(long ctx);

    /**
     * Watch standard input and end the process on end of file, on the core's
     * own thread, as the core's SIGTERM handler ends it. Context-free.
     */
    public static native void watchStdinEof();

    /** Destroy the context. The handle is dead afterwards. */
    public static native void contextFree(long ctx);
}
