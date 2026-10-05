/**
 * The unit of user code: a tasklet, the context handed to it, and the values it may request.
 *
 * <p>{@link com.flezha.sdk.tasklet.Tasklet} is parameterised by three types: the input value, the
 * per-cell state and the emitted output. {@link java.lang.Void} as the state keeps no state, and
 * as the output declares no output channel. The runtime reads the three from the class's
 * {@code implements} clause, and its name and settings from its
 * {@link com.flezha.sdk.tasklet.Tasklet.Spec @Tasklet.Spec} annotation. Nothing is generated: the
 * class the author writes is the class the wiring names.
 *
 * <p>Invocation is serialised per cell. Two messages for the same key are never handled
 * concurrently, so a tasklet requires no internal locking around its own state. Messages for
 * distinct keys are handled concurrently, on the same host or on different hosts, and a tasklet
 * instance is held per cell rather than shared across them.
 *
 * <p>{@link com.flezha.sdk.tasklet.Ctx} is the context passed to every invocation. It holds the
 * cell's state through {@code state()} and {@code change(...)}, supplies output through
 * {@code emit} and {@code emitKeyed}, the tasklet's clock
 * through {@code now()}, a replay-stable identifier through {@code stableId()}, checkpoint control,
 * timer registration, and the steps spawn request described below.
 *
 * <p>Recovery re-executes user code. A tasklet must therefore be a deterministic function of its
 * declared inputs: reading {@code System.currentTimeMillis()} or generating a random identifier
 * produces a different result on replay, and the rebuilt state then diverges from the state that
 * was originally observed. {@code ctx.now()} and {@link com.flezha.sdk.tasklet.StableId} exist to
 * remove both sources of divergence.
 *
 * <p>{@link com.flezha.sdk.tasklet.SpawnRequests}, {@link com.flezha.sdk.tasklet.SpawnOptions},
 * {@link com.flezha.sdk.tasklet.IfRunning}, {@link com.flezha.sdk.tasklet.SpawnHandle},
 * {@link com.flezha.sdk.tasklet.SpawnResult} and {@link com.flezha.sdk.tasklet.SpawnOutcome}
 * cover tasklet-requested steps relay spawning, in which a tasklet asks the controller for a
 * worker instance rather than dispatching one itself. Without an id the controller generates one
 * and starts a new instance; with one, the request is resolved controller-side, so concurrent
 * requests against that id converge on a single instance.
 * {@link com.flezha.sdk.tasklet.TaskletException} is the checked failure type a tasklet
 * raises; a raised failure is classified, retried according to the channel's policy, and parked in
 * the dead-letter queue if the retry ladder is exhausted.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.tasklet;
