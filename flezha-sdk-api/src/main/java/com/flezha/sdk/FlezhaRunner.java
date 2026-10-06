package com.flezha.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.control.Control;
import com.flezha.sdk.internal.RegistryCore;
import com.flezha.sdk.internal.RunnerHandle;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.relay.Relay;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This JVM's runner: create → declare ({@link #registerFactory} /
 * {@link #unregisterFactory}) → start on one object, returned by
 * {@link Flezha#createRunner(String)}.
 * Relay types may be declared and removed before AND after
 * {@link #start()}, until the started runner is closed; a post-start change
 * is declared to the core immediately and reaches the controller on the next
 * connect/reconnect init frame, which carries the runner's full factory set.
 * A closed runner refuses every declaration and removal.
 *
 * <p>At most one runner is started per JVM (the slot is claimed by CAS inside
 * {@link #start()}); reach the active one from code that does not control
 * startup ordering via {@link Flezha#currentRunner()}.
 *
 * <p>{@code FlezhaRunner} is {@link AutoCloseable}: {@link #close()} on a
 * started runner runs the graceful-shutdown sequence
 * (ship-fresh-state-on-sdk-shutdown), releases the singleton slot, and wakes
 * every thread blocked in {@link #awaitTermination()}; on a never-started
 * runner it is a no-op. The same sequence is registered as a JVM shutdown
 * hook so SIGTERM (Kubernetes pre-stop) triggers it without an explicit
 * {@code close()} call.
 *
 * <p>Method names and lifecycle verbs follow
 * {@code docs/sdk/naming_conventions.md} (repository root), mirrored 1:1 with
 * the Rust SDK's {@code FlezhaRunner}.
 */
public final class FlezhaRunner implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String namespace;

    /**
     * This runner's declared factory set — per-runner (a later runner starts
     * empty; generation 2 must re-define its types). The runtime-side class
     * registry stays process-global behind it.
     */
    private final RegistryCore factories = new RegistryCore("com.flezha.sdk.runtime.relay.RelayClassRegistry");

    private final AtomicReference<RunnerHandle> handle = new AtomicReference<>();

    /** Set once by the first {@link #start()}; a second start() is rejected forever. */
    private final AtomicBoolean everStarted = new AtomicBoolean(false);

    /**
     * Set by {@link #close()} on a started runner, whose core context the
     * shutdown then frees; every later declaration and removal is refused
     * rather than carried to it. A never-started runner has no context, so
     * closing one leaves it mutable.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    FlezhaRunner(String namespace) {
        this.namespace = namespace;
    }

    /** The namespace this runner was created for — its stable identity. */
    @Nonnull
    public String getNamespace() {
        return namespace;
    }

    /**
     * Declare a relay class the runtime can spawn instances of later (via the controller's
     * {@code AddRelay}). Calls the class's {@code channels(...)} and reads its settings once,
     * and records the topology in the class registry.
     * Legal before and after {@link #start()}. Invalid authoring is reported here, at the declaring
     * call site, as a {@link FlezhaException} whose code names the refusal.
     *
     * <p>A short form is registered by the tasklet class the author wrote, annotated
     * {@link com.flezha.sdk.authoring.Relay @Relay}: the call reads the annotation and
     * declares the one-tasklet relay it describes. Nothing is generated.
     *
     * @throws FlezhaException on a duplicate factory id ({@code topology.duplicate_factory_id}), when
     *     the class is neither a relay nor a short form ({@code topology.no_relay_exported}),
     *     when its authoring is refused, and on a closed runner ({@code runtime.runner_closed}).
     */
    @Nonnull
    public FlezhaRunner registerFactory(Class<?> relayClass) {
        open();
        checked(relayClass, "registerFactory()").register(relayClass);
        syncFactoriesIfStarted();
        return this;
    }

    /**
     * Remove a declared relay class from this runner — the inverse of
     * {@link #registerFactory(Class)}, taking what it took. Legal before and after
     * {@link #start()}.
     *
     * @throws FlezhaException {@code runtime.class_not_registered} if the class's factory id is not
     *     currently defined on this runner, {@code runtime.relay_type_in_use} if the runner is
     *     started and live relay instances of the factory exist (the type stays defined),
     *     {@code runtime.runner_closed} on a closed runner, and
     *     {@code topology.no_relay_exported} as {@link #registerFactory} names it.
     */
    @Nonnull
    public FlezhaRunner unregisterFactory(Class<?> relayClass) {
        open();
        return remove(checked(relayClass, "unregisterFactory()"), relayClass);
    }

    /**
     * The registry, for a class that is a relay or a short form; anything else is refused by
     * the contract's name for it ({@code docs/sdk/authoring_contract.md} §5.1).
     */
    private RegistryCore checked(Class<?> declared, String what) {
        if (declared == null) {
            throw new IllegalArgumentException(what + ": the relay class must not be null");
        }
        if (!Relay.class.isAssignableFrom(declared)
                && !declared.isAnnotationPresent(com.flezha.sdk.authoring.Relay.class)) {
            throw FlezhaException.of("topology.no_relay_exported", Map.of("module", declared.getName()));
        }
        return factories;
    }

    /**
     * Send the SDK's diagnostics to {@code logger} instead of the default,
     * which writes {@code [flezha] <message>} to standard error at the level
     * {@code FLEZHA_LOG_LEVEL} names. The output is JVM-wide and survives
     * {@link #close()}, so the diagnostics a runner writes while shutting down
     * still reach it. Legal before {@link #start()}, and after.
     *
     * <p>{@link Logger} is a functional interface, so routing into a pipeline
     * the host already runs is one lambda. SLF4J, whose {@code {}} placeholder
     * the SDK's messages already use:
     *
     * <pre>{@code
     * org.slf4j.Logger slf4j = LoggerFactory.getLogger("flezha");
     * runner.logger((level, format, args) ->
     *         slf4j.atLevel(Level.valueOf(level.name())).log(format, args));
     * }</pre>
     *
     * <p>The supplied logger decides for itself what it writes:
     * {@code FLEZHA_LOG_LEVEL} filters the default output and nothing else.
     */
    @Nonnull
    public FlezhaRunner logger(Logger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger must not be null");
        }
        Flezha.LOGGER.set(logger);
        return this;
    }

    private FlezhaRunner remove(RegistryCore core, Class<?> cls) {
        String factoryId = core.definedFactoryIdOf(cls);
        RunnerHandle h = handle.get();
        if (h != null) {
            // Started: the runtime owns the race-free live-instance check
            // (unregister-first, re-register + throw on a live hit).
            h.removeType(factoryId);
            core.dropEntry(factoryId);
        } else {
            // Never started: no instance can exist; unregister directly.
            core.remove(factoryId);
        }
        syncFactoriesIfStarted();
        return this;
    }

    /**
     * Connect to the controller and return {@code this} for chaining. Blocks
     * only for the connect handshake (send init, wait for
     * {@code AssignRunnerId} — retried with exponential backoff until the
     * controller is reachable), never for the runner's ongoing lifetime.
     * Claims the JVM-wide singleton slot by compare-and-set; the loser fails
     * WITHOUT connecting, mutating shared state, or spawning a thread.
     * Starting with zero declared relay types is legal — the init frame
     * carries an empty factory list, and types may be defined later.
     *
     * @throws FlezhaException {@code runtime.runner_already_running} if a runner is already
     *     running in this JVM, or on a second {@code start()} of this runner; the core's own code
     *     when the connect is refused (for example {@code runner.namespace_not_proven}).
     */
    @Nonnull
    public FlezhaRunner start() {
        if (!everStarted.compareAndSet(false, true)) {
            throw FlezhaException.of("runtime.runner_already_running", Map.of("namespace", namespace));
        }
        // Claim the JVM-wide slot: exactly one concurrent start() wins, and
        // the loser must not connect or mutate any shared state.
        if (!Flezha.RUNNING_NAMESPACE.compareAndSet(null, namespace)) {
            everStarted.set(false);
            String running = Flezha.RUNNING_NAMESPACE.get();
            throw FlezhaException.of(
                    "runtime.runner_already_running", Map.of("namespace", running == null ? "" : running));
        }
        try {
            handle.set(startRuntime());
            Flezha.CURRENT.set(this);
            return this;
        } catch (RuntimeException | Error e) {
            handle.set(null);
            everStarted.set(false);
            Flezha.releaseSlot();
            throw e;
        }
    }

    /**
     * Sugar for standalone binaries: {@link #start()} followed by
     * {@link #awaitTermination()}. Blocks the calling thread until the runner
     * shuts down — no keep-alive loop needed in {@code main()}.
     */
    public void run() throws InterruptedException {
        start().awaitTermination();
    }

    /**
     * Administer this namespace from ordinary application code: start and
     * stop relays, enable and disable plugins, read what is running.
     *
     * <p>The controller judges every call as the identity this connection
     * proved, so no credential is held here and nothing expires. A user
     * grants a namespace's applications the authority once, with
     * {@code flz role bind runner@<namespace> app <namespace>}; until then
     * every call is refused.
     *
     * @throws FlezhaException {@code runtime.runner_not_started} on a never-started runner.
     */
    @Nonnull
    public Control control() {
        return new Control(started());
    }

    /**
     * Send {@code message} to the entry channel {@code channel} of
     * {@code relayId} through the controller, under the same role check as
     * {@link #control()}, and answer its verdict:
     * {@code {"to": <relay>, "verdict": "delivered" | "absorbed" | "suspended"}}.
     *
     * @throws FlezhaException {@code runtime.runner_not_started} on a never-started runner.
     */
    @Nonnull
    public JsonNode send(String relayId, String message, String channel) {
        return send(relayId, message, channel, SendOpts.of());
    }

    /**
     * {@link #send(String, String, String)} with a message id or a partition
     * key, or locally: in process to a relay bound on this runner,
     * answering {@code null}. See {@link SendOpts#local()} for what a local
     * send gives up.
     *
     * @throws FlezhaException {@code usage.options_conflict} for a local send
     *     with an id or a key; {@code runtime.relay_not_local} for a local
     *     send to a relay this runner does not hold.
     */
    @Nullable
    public JsonNode send(String relayId, String message, String channel, SendOpts opts) {
        String answer = started()
                .send(relayId, channel, message.getBytes(StandardCharsets.UTF_8), opts.msgId, opts.key, opts.local);
        JsonNode verdict = Control.read("Relay.send", answer);
        return verdict.isNull() ? null : verdict;
    }

    /**
     * Call the entry channel {@code channel} of {@code relayId} with {@code value} through the
     * controller, under the same role check as {@link #send(String, String, String)}, and answer
     * what the relay answers through its outputs.
     *
     * <p>The value is encoded as an output with no codec encodes it: bytes unchanged, a string as
     * UTF-8, anything else as JSON. A JSON answer reads into {@code answer}, a text answer as a
     * {@code String}, a binary one as {@code byte[]}. A channel that does not answer reads as
     * {@code {"accepted": true}}, so ask for a {@link JsonNode} or a {@code Map} there.
     *
     * <pre>{@code
     * Placed placed = runner.call("orders-eu", "place", new Order("EUR/USD", 1000), Placed.class);
     * }</pre>
     *
     * @throws FlezhaException the controller's code when it refuses the call, for example
     *     {@code api.channel_unknown}; {@code control.unreadable_answer} when the answer does not
     *     read as {@code answer}; {@code runtime.runner_not_started} on a never-started runner.
     */
    public <A> A call(String relayId, String channel, Object value, Class<A> answer) {
        String payload = Base64.getEncoder().encodeToString(PlainPayload.encode(value));
        ObjectNode opts = JsonNodeFactory.instance.objectNode().put("payload", "base64");
        JsonNode reply = control().call("Relay.call_channel", relayId, channel, payload, opts);
        try {
            return JSON.treeToValue(reply.has("answer_codec") ? reply.path("answer") : reply, answer);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw FlezhaException.of("control.unreadable_answer", Map.of("verb", "Relay.call_channel"), e);
        }
    }

    /**
     * The ids of the relay instances bound on this runner now, a partition
     * member by its member id, read without the controller.
     *
     * @throws FlezhaException {@code runtime.runner_not_started} on a never-started runner.
     */
    @Nonnull
    public List<String> getRelays() {
        return started().relays();
    }

    /**
     * Block the calling thread until the runner shuts down — via
     * {@link #close()} from another thread, or SIGTERM-triggered graceful
     * shutdown. Replaces every hand-rolled keep-alive loop; a standalone
     * {@code main()} that wants this behaviour in one call uses {@link #run()}.
     *
     * @throws FlezhaException {@code runtime.runner_not_started} on a never-started runner.
     */
    public void awaitTermination() throws InterruptedException {
        started().awaitTermination();
    }

    /**
     * Run the graceful-shutdown sequence (ship-fresh-state-on-sdk-shutdown),
     * release the JVM-wide runner slot and wake every thread blocked in
     * {@link #awaitTermination()}. A no-op on a never-started runner;
     * idempotent across SIGTERM + explicit close.
     */
    @Override
    public void close() {
        RunnerHandle h = handle.get();
        if (h != null) {
            // Marked before the shutdown runs: the sequence ends in
            // contextFree, and nothing may declare into the context after it.
            closed.set(true);
            h.close();
        }
    }

    private RunnerHandle started() {
        RunnerHandle h = handle.get();
        if (h == null) {
            throw FlezhaException.of("runtime.runner_not_started", Map.of());
        }
        return h;
    }

    /**
     * Refuse a declaration or removal on a closed runner. {@link #close()}
     * destroys the core context, so carrying the change to the handle would
     * hand a freed pointer back across the JNI boundary.
     */
    private void open() {
        if (closed.get()) {
            throw FlezhaException.of("runtime.runner_closed", Map.of("namespace", namespace));
        }
    }

    /** This runner's full current factory set. */
    private List<String> declaredFactoryIds() {
        return factories.factoryIds();
    }

    /**
     * Declare the FULL current factory set to the core after a post-start
     * define/remove — full-set semantics make a redeclaration idempotent, and
     * the core carries the set to the controller on the next
     * connect/reconnect init frame. A no-op on a never-started runner.
     */
    private void syncFactoriesIfStarted() {
        RunnerHandle h = handle.get();
        if (h != null) {
            h.syncFactories(declaredFactoryIds());
        }
    }

    /**
     * Invoke the runtime implementation via {@link RuntimeBridge}. It lives in
     * {@code flezha-sdk-runtime} so this api module compiles standalone. The
     * {@code Supplier} feeds the initial handshake AND every reconnect
     * re-handshake (so post-start mutations resync naturally); the
     * {@code Runnable} is the release hook for the JVM-wide slot, run exactly
     * once when the runner closes.
     */
    private RunnerHandle startRuntime() {
        return (RunnerHandle) RuntimeBridge.invoke(
                "com.flezha.sdk.runtime.FlezhaImpl",
                "start",
                new Class<?>[] {String.class, Supplier.class, Runnable.class},
                "Failed to start the Flezha runner",
                namespace,
                (Supplier<List<String>>) this::declaredFactoryIds,
                (Runnable) Flezha::releaseSlot);
    }
}
