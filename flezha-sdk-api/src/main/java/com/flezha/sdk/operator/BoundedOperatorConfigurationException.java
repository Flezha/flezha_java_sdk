package com.flezha.sdk.operator;

/**
 * Thrown at {@link com.flezha.sdk.FlezhaRunner#registerFactory declaration} time when a
 * {@link BoundedOperator}'s authoring is invalid: an external-stop-only operator (no
 * self-termination and no {@code maxDuration()}), or a {@link BoundedOperator#channels} graph the
 * core refuses (no tasklet, a cycle, a disconnected tasklet, no entry
 * channel, or an unbounded-only setting such as the effectively-once tier), in which case the message
 * carries the refusal's name.
 *
 * <p>Unchecked, mirroring {@link com.flezha.sdk.operator.UnboundedOperatorConfigurationException}
 * — it surfaces a programming error in the authoring, caught at registration
 * rather than at run time.
 */
public class BoundedOperatorConfigurationException extends RuntimeException {

    public BoundedOperatorConfigurationException(String message) {
        super(message);
    }

    public BoundedOperatorConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
