/**
 * Repairing an already-published result when an input arrives after its interval closed.
 *
 * <p>Trades carry their own timestamps, and one occasionally arrives minutes after the candle it
 * belongs to was computed and emitted. The alternatives are to discard it, which loses data, or to
 * treat it as current, which corrupts the interval it lands in. Neither is satisfactory, so the
 * runtime instead replays the affected cell with the late message in its correct position and
 * re-emits the corrected result.
 *
 * <p>Two declarations make this possible. The tasklet writes {@code eventTime}, which reads the
 * timestamp out of a message, and writing it is what makes the clock event time: {@code ctx.now()}
 * returns the message's own timestamp rather than processing time, which is what gives "late" a
 * meaning and what places a replayed message in the interval it always belonged to.
 *
 * <p>The emit is keyed by the interval bucket, so the corrected candle overwrites the stale record
 * rather than appending a second one. This is the same keyed-emit mechanism the
 * {@code effectivelyonce} example relies on, applied to a different problem.
 *
 * <p>{@code correctionWindow} bounds how far back the runtime will replay, and it is not free: the
 * window sizes the controller's input-log retention, so it should reflect the lateness actually
 * expected. {@link com.flezha.sdk.channel.BeyondWindowPolicy} governs a message older than the
 * window, treated here as a fresh arrival.
 */
package com.flezha.examples.corrections;
