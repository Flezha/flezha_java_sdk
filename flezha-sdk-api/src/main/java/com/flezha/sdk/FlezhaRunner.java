package com.flezha.sdk;

import com.flezha.sdk.admin.Admin;
import com.flezha.sdk.internal.RegistryCore;
import com.flezha.sdk.internal.RunnerHandle;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.UnboundedOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * This JVM's runner: create → declare ({@link #registerFactory} /
 * {@link #unregisterFactory}) → start on one object, returned by
 * {@link Flezha#createRunner(String)}.
 * Operator types may be declared and removed before AND after
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

    private final String namespace;

    /**
     * This runner's declared factory sets — per-runner (a later runner starts
     * empty; generation 2 must re-define its types). The runtime-side class
     * registries stay process-global behind these.
     */
    private final RegistryCore stream =
            new RegistryCore("com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry", "operator");

    private final RegistryCore bounded =
            new RegistryCore("com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry", "bounded");

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
     * Declare an operator class the runtime can spawn instances of later (via the controller's
     * {@code AddOperator}), of either kind: the kind is read from the interface the class
     * implements, never named by the call. Calls the class's {@code channels(...)} once and
     * records the topology in the class registry of its kind.
     * Legal before and after {@link #start()}. Invalid authoring is reported here, at the declaring
     * call site, as {@code UnboundedOperatorConfigurationException} or
     * {@code BoundedOperatorConfigurationException}.
     *
     * <p>A short form is registered by the tasklet class the author wrote, annotated
     * {@link com.flezha.sdk.authoring.UnboundedOperator @UnboundedOperator} or
     * {@link com.flezha.sdk.authoring.BoundedOperator @BoundedOperator}: the call reads the
     * annotation and declares the one-tasklet operator it describes. Nothing is generated.
     *
     * <p>Unbounded and bounded operators share one factory-id namespace per runner, so a second class under
     * an id this runner already holds is refused whichever kind either one is.
     *
     * @throws IllegalArgumentException on a duplicate factory id (the message begins with
     *     {@code DuplicateFactoryId}), when the class implements both kind interfaces or carries both
     *     annotations ({@code AmbiguousOperatorKind}), when it is neither an operator nor a short
     *     form ({@code UnknownOperatorKind}), or when a short form is refused.
     * @throws IllegalStateException on a closed runner.
     */
    @Nonnull
    public FlezhaRunner registerFactory(Class<?> operatorClass) {
        open("registerFactory()");
        coreOf(operatorClass, "registerFactory()").register(operatorClass);
        syncFactoriesIfStarted();
        return this;
    }

    /**
     * Remove a declared operator class from this runner — the inverse of
     * {@link #registerFactory(Class)}, taking what it took. Legal before and after
     * {@link #start()}.
     *
     * @throws IllegalArgumentException if the class's factory id is not currently defined on this
     *     runner, or its kind cannot be read.
     * @throws OperatorTypeInUseException if the runner is started and live operator instances of
     *     the factory exist — the type stays defined.
     * @throws IllegalStateException on a closed runner.
     */
    @Nonnull
    public FlezhaRunner unregisterFactory(Class<?> operatorClass) {
        open("unregisterFactory()");
        return remove(coreOf(operatorClass, "unregisterFactory()"), operatorClass);
    }

    /**
     * The registry of the kind a class declares, read from the interface it implements or, for a
     * short form, from its annotation. A class of both kinds, or of neither, is refused by the
     * contract's name for it ({@code docs/sdk/authoring_contract.md} §5.1).
     */
    private RegistryCore coreOf(Class<?> declared, String what) {
        if (declared == null) {
            throw new IllegalArgumentException(what + ": the operator class must not be null");
        }
        boolean isUnbounded = UnboundedOperator.class.isAssignableFrom(declared)
                || declared.isAnnotationPresent(com.flezha.sdk.authoring.UnboundedOperator.class);
        boolean isBounded = BoundedOperator.class.isAssignableFrom(declared)
                || declared.isAnnotationPresent(com.flezha.sdk.authoring.BoundedOperator.class);
        if (isUnbounded && isBounded) {
            throw new IllegalArgumentException("AmbiguousOperatorKind: " + declared.getName()
                    + " declares both an unbounded and a bounded operator, so its kind cannot be read");
        }
        if (!isUnbounded && !isBounded) {
            throw new IllegalArgumentException("UnknownOperatorKind: " + declared.getName()
                    + " implements neither UnboundedOperator nor BoundedOperator, and is annotated with"
                    + " neither @UnboundedOperator nor @BoundedOperator");
        }
        return isBounded ? bounded : stream;
    }

    /**
     * Send the SDK's diagnostics to {@code logger} instead of the default,
     * which writes {@code [flezha] <message>} to standard error at the level
     * {@code FLEZHA_LOG_LEVEL} names. The sink is JVM-wide and survives
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
     * {@code FLEZHA_LOG_LEVEL} filters the default sink and nothing else.
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
     * Starting with zero declared operator types is legal — the init frame
     * carries an empty factory list, and types may be defined later.
     *
     * @throws IllegalStateException if a runner is already running in this JVM
     *     (names the running namespace), or on a second {@code start()} of
     *     this runner.
     */
    @Nonnull
    public FlezhaRunner start() {
        if (!everStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("this runner for namespace '" + namespace + "' was already started; "
                    + "a FlezhaRunner starts at most once");
        }
        // Claim the JVM-wide slot: exactly one concurrent start() wins, and
        // the loser must not connect or mutate any shared state.
        if (!Flezha.RUNNING_NAMESPACE.compareAndSet(null, namespace)) {
            everStarted.set(false);
            String running = Flezha.RUNNING_NAMESPACE.get();
            throw new IllegalStateException("a Flezha runner is already running in this JVM"
                    + (running != null ? " for namespace '" + running + "'" : "")
                    + "; at most one runner may be started per process — close() it before starting another");
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
     * Administer this namespace from ordinary application code: start, stop
     * and feed operators, enable and disable plugins, read what is running.
     *
     * <p>The controller judges every call as the identity this connection
     * proved, so no credential is held here and nothing expires. A user
     * grants a namespace's applications the authority once, with
     * {@code flz role bind runner@<namespace> app <namespace>}; until then
     * every call is refused.
     *
     * @throws IllegalStateException on a never-started runner.
     */
    @Nonnull
    public Admin admin() {
        return new Admin(started("admin()"));
    }

    /**
     * Returns a snapshot of currently-spawned Operator instances keyed by
     * what the wire calls {@code operator_id}.
     *
     * @throws IllegalStateException on a never-started runner.
     */
    @Nonnull
    public Map<String, ?> getOperators() {
        return started("getOperators()").operators();
    }

    /**
     * Block the calling thread until the runner shuts down — via
     * {@link #close()} from another thread, or SIGTERM-triggered graceful
     * shutdown. Replaces every hand-rolled keep-alive loop; a standalone
     * {@code main()} that wants this behaviour in one call uses {@link #run()}.
     *
     * @throws IllegalStateException on a never-started runner.
     */
    public void awaitTermination() throws InterruptedException {
        started("awaitTermination()").awaitTermination();
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

    private RunnerHandle started(String what) {
        RunnerHandle h = handle.get();
        if (h == null) {
            throw new IllegalStateException(what + " requires a started runner — runner not started");
        }
        return h;
    }

    /**
     * Refuse a declaration or removal on a closed runner. {@link #close()}
     * destroys the core context, so carrying the change to the handle would
     * hand a freed pointer back across the JNI boundary.
     */
    private void open(String what) {
        if (closed.get()) {
            throw new IllegalStateException(what + " on the closed runner for namespace '" + namespace
                    + "'; a closed runner declares nothing — create a new one");
        }
    }

    /** This runner's full current factory set: stream first, then bounded operator. */
    private List<String> declaredFactoryIds() {
        List<String> ids = new ArrayList<>(stream.factoryIds());
        ids.addAll(bounded.factoryIds());
        return ids;
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
