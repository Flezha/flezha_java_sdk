package com.flezha.sdk.operative;

/**
 * Thrown at {@link com.flezha.sdk.FlezhaRunner#registerFactory declaration} time when a
 * {@link WorkflowOperative}'s authoring is invalid: an external-stop-only
 * operative (no self-termination and no {@code maxDuration()}), or a
 * {@link WorkflowOperative#tasklets} declaration that is not a single connected
 * linear chain (no tasklet, branching, a cycle, a dynamic-cell tasklet, a
 * disconnected tasklet, or no entry channel).
 *
 * <p>Unchecked, mirroring {@link com.flezha.sdk.operative.StreamOperativeConfigurationException}
 * — it surfaces a programming error in the authoring, caught at registration
 * rather than at run time.
 */
public class WorkflowOperativeConfigurationException extends RuntimeException {

    /** More than one attempt with no backoff would hot-spin the failing cell. */
    public static WorkflowOperativeConfigurationException zeroRetryBackoff(String factoryId) {
        return new WorkflowOperativeConfigurationException("ZeroRetryBackoff: workflow operative `" + factoryId
                + "` declares retryAttempts() > 1 with a zero retryBackoff(); the ladder would hot-spin. "
                + "Declare a backoff, or set retryAttempts() to 1 for fail-fast");
    }

    public WorkflowOperativeConfigurationException(String message) {
        super(message);
    }
}
