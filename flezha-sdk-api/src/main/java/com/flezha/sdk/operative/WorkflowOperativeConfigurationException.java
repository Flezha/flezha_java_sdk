package com.flezha.sdk.operative;

/**
 * Thrown at {@link com.flezha.sdk.FlezhaRunner#registerFactory declaration} time when a
 * {@link WorkflowOperative}'s authoring is invalid: an external-stop-only operative (no
 * self-termination and no {@code maxDuration()}), or a {@link WorkflowOperative#channels} graph the
 * core refuses (no tasklet, a cycle, a disconnected tasklet, no entry
 * channel, or a stream-only setting such as the effectively-once tier), in which case the message
 * carries the refusal's name.
 *
 * <p>Unchecked, mirroring {@link com.flezha.sdk.operative.StreamOperativeConfigurationException}
 * — it surfaces a programming error in the authoring, caught at registration
 * rather than at run time.
 */
public class WorkflowOperativeConfigurationException extends RuntimeException {

    public WorkflowOperativeConfigurationException(String message) {
        super(message);
    }

    public WorkflowOperativeConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
