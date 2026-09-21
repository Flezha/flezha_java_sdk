/**
 * The unit of user code: a tasklet, the context handed to it, and the values it may request.
 *
 * <p>{@link com.flezha.sdk.tasklet.Tasklet} is parameterised by six types, in declaration
 * order: the input value, the per-cell state, the emitted output, the change type used to record
 * state mutations, the clock the tasklet reads, and its configuration type. A tasklet that
 * keeps no state declares {@link com.flezha.sdk.state.NoChange}; one that takes no configuration
 * declares {@link java.lang.Void}. The runtime recovers these six as {@code Class} handles from the
 * {@code implements} clause, so they must be concrete there or supplied by overriding the
 * corresponding accessor.
 *
 * <p>Invocation is serialised per cell. Two messages for the same key are never handled
 * concurrently, so a tasklet requires no internal locking around its own state. Messages for
 * distinct keys are handled concurrently, on the same host or on different hosts, and a tasklet
 * instance is held per cell rather than shared across them.
 *
 * <p>{@link com.flezha.sdk.tasklet.TaskletCtx} is the runtime handle passed to every
 * invocation. It supplies output through {@code emit} and {@code emitKeyed}, the declared clock
 * through {@code now()}, a replay-stable identifier through {@code stableId()}, checkpoint control,
 * timer registration, and the workflow spawn request described below.
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
 * cover tasklet-requested workflow spawning, in which a tasklet asks the controller for a
 * worker instance rather than dispatching one itself. Without an id the controller generates one
 * and starts a new instance; with one, the request is resolved controller-side, so concurrent
 * requests against that id converge on a single instance.
 * {@link com.flezha.sdk.tasklet.TaskletException} is the checked failure type a tasklet
 * raises; a raised failure is classified, retried according to the channel's policy, and parked in
 * the dead-letter queue if the retry ladder is exhausted.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.tasklet;
