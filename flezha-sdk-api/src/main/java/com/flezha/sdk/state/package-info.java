/**
 * Per-cell state and the change log that makes it durable.
 *
 * <p>State is not persisted by serialising the state object. It is persisted as an ordered log of
 * {@link com.flezha.sdk.state.Change} values, and the object is rebuilt after a restart by
 * replaying that log over the initial state, on top of the most recent snapshot where one exists.
 * A field assigned directly from {@code run} is therefore absent from the log and absent from the
 * rebuilt object.
 *
 * <p>Consequently, {@code ctx.change(...)} is the only supported way to mutate state, and
 * {@link com.flezha.sdk.state.Change#applyTo} is the only method that writes fields. The runtime
 * invokes it once when a change is recorded and again for every logged change during a rebuild,
 * so it must apply the change and do nothing else: no emitting, no clock reads, no external
 * calls. This failure mode is silent under normal operation, because a direct assignment behaves
 * correctly for as long as the process survives.
 *
 * <p>{@link com.flezha.sdk.state.CoalesceKey} allows changes carrying equal keys to collapse to the
 * latest within a flush window, which suits "current value of X" updates and is wrong for
 * additive deltas, since collapsing those loses every value but the last.
 *
 * <p>{@link com.flezha.sdk.state.RestoreInitPayload} carries a snapshot and change log for the
 * test harness to restore. {@link com.flezha.sdk.state.ReplayMismatch} reports a rebuild that did
 * not reproduce the observed state, which is the diagnostic for non-deterministic user code.
 * {@link com.flezha.sdk.state.TimerKind} distinguishes the timer families delivered to
 * {@code onTimer}.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.state;
