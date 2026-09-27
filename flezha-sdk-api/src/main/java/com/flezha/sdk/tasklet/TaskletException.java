package com.flezha.sdk.tasklet;

/**
 * Recoverable error thrown by {@link Tasklet#run} or {@link Ctx} emit
 * methods. The runtime catches this, logs against
 * {@code (operatorInstance, tasklet, cell)}, discards any partial emits, and
 * continues dispatching subsequent inputs on the same Cell.
 */
public class TaskletException extends Exception {

    public TaskletException(String message) {
        super(message);
    }

    public TaskletException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when {@code ctx.emitWithMeta(...)} is called on a channel
     * declared as inter-tasklet. Per-channel reserved-key semantics apply only
     * to plugin-bound channels.
     */
    public static final class MetadataNotAllowedOnInterTaskletChannel extends TaskletException {
        private final String channel;

        public MetadataNotAllowedOnInterTaskletChannel(String channel) {
            super("metadata is only valid on plugin-bound channels; channel `" + channel + "` is inter-tasklet");
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }
    }

    /**
     * Thrown when a plain {@code emit} is called on a channel declared
     * {@code .keyed()} by a single-cell Tasklet — one with no Cell key
     * to emit under. A keyed Cell's plain emit carries its own key instead;
     * an output whose key differs uses {@code ctx.emitKeyed(...)}.
     */
    public static final class KeyRequiredOnKeyedChannel extends TaskletException {
        private final String channel;

        public KeyRequiredOnKeyedChannel(String channel) {
            super("channel `" + channel
                    + "` is declared keyed and this tasklet is single-cell, so there is no Cell key to emit"
                    + " under; use emitKeyed(key, value)");
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }
    }

    /** Thrown when {@code ctx.emitKeyed(...)} targets a channel not declared {@code .keyed()}. */
    public static final class KeyNotAllowedOnNonKeyedChannel extends TaskletException {
        private final String channel;

        public KeyNotAllowedOnNonKeyedChannel(String channel) {
            super("channel `" + channel + "` is not declared keyed; use emit(value)");
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }
    }

    /**
     * Thrown when user metadata carries a key in the reserved {@code flz.}
     * namespace. The runtime's identity stamps ({@code flz.cell},
     * {@code flz.seq}, {@code flz.key}) live there, so a user key is rejected
     * rather than dropped — otherwise a stamp could be spoofed or clobbered.
     */
    public static final class ReservedMetadataKey extends TaskletException {
        private final String key;

        public ReservedMetadataKey(String key) {
            super("metadata key `" + key + "` is reserved: the `" + Ctx.RESERVED_METADATA_PREFIX
                    + "` namespace belongs to the runtime");
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * Thrown when an {@code emit} or {@code emitWithMeta} call references a
     * channel name that was not declared on this UnboundedOperator's topology.
     */
    public static final class UnknownChannel extends TaskletException {
        private final String channel;

        public UnknownChannel(String channel) {
            super("channel `" + channel + "` is not declared on this operator");
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }
    }

    /**
     * Classifies a domain failure as non-retryable: the dispatcher parks the
     * payload to the dead-letter queue immediately, skipping the channel's
     * remaining retry budget. Use for failures that can never succeed on
     * re-run (malformed payload, permanent semantic violation).
     */
    public static final class NonRetryable extends TaskletException {

        public NonRetryable(String message) {
            super("non-retryable tasklet error: " + message);
        }

        public NonRetryable(String message, Throwable cause) {
            super("non-retryable tasklet error: " + message, cause);
        }
    }

    /**
     * Thrown when a tasklet whose clock does not derive time from the input
     * registers an event-time timer: without an event-time clock
     * there is no high-water for it to fire against.
     */
    public static final class EventTimeTimerWithoutEventTimeClock extends TaskletException {

        public EventTimeTimerWithoutEventTimeClock(String tasklet, String clock) {
            super("tasklet `" + tasklet + "` declares clock `" + clock
                    + "`, which does not derive time from the input; an event-time timer needs"
                    + " an event-time clock, which writing eventTime(input) declares");
        }
    }

    /**
     * Thrown when a Cell already holds {@code @Tasklet.Spec(maxTimersPerCell)} pending
     * timers and registers one more. A per-Cell timer set is author-controlled
     * memory multiplied by an unbounded Cell count; the cap is what keeps a
     * runaway registration a refusal rather than an OOM.
     */
    public static final class TimerCapExceeded extends TaskletException {

        public TimerCapExceeded(String tasklet, int max) {
            super("tasklet `" + tasklet + "` already holds its maximum of " + max
                    + " pending timers for this Cell; cancel one or raise @Tasklet.Spec(maxTimersPerCell)");
        }
    }

    /**
     * Thrown where timers do not apply: a {@code compensate} hook, in either kind. The
     * refusal is structural: that context carries no timer session at all. Like any
     * other exception a tasklet throws, it fails the attempt.
     */
    public static final class TimersUnavailable extends TaskletException {

        public TimersUnavailable() {
            super("TimersUnavailable: timers are not available in this context; only a tasklet's"
                    + " `run` / `onTimer` may register them");
        }
    }

    /**
     * Thrown when a hybrid channel's user-supplied plugin serializer throws.
     * Consumer fan-out has already completed by the time this surfaces — the
     * inter-tasklet consumers received the typed value; only the plugin-wire
     * emission was skipped. The dispatcher thread stays alive.
     */
    public static final class PluginSerializerFailure extends TaskletException {
        private final String channel;

        public PluginSerializerFailure(String channel, String message, Throwable cause) {
            super("plugin serializer for channel `" + channel + "` failed: " + message, cause);
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }
    }
}
