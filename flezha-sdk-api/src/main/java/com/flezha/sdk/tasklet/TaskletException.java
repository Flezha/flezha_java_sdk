package com.flezha.sdk.tasklet;

/**
 * The author's own recoverable error, thrown from {@link Tasklet#run}. The runtime catches this,
 * logs against {@code (operatorInstance, tasklet, cell)}, discards any partial emits, and continues
 * dispatching subsequent inputs on the same Cell. A refusal Flezha makes itself, such as an emit on
 * a channel the tasklet does not own, is a {@link com.flezha.sdk.FlezhaException} instead.
 */
public class TaskletException extends Exception {

    public TaskletException(String message) {
        super(message);
    }

    public TaskletException(String message, Throwable cause) {
        super(message, cause);
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
}
