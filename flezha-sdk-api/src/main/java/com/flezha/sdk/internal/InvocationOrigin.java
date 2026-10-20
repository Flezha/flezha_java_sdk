package com.flezha.sdk.internal;

/**
 * Where an invocation came from, as a spawn request carries it: the activation the poll core
 * handed out and the input-log offset of the item it runs. The runtime's context implements it; a
 * context that does not names neither.
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls this.
 */
public interface InvocationOrigin {

    /** The poll core's token for the activation every spawn handle belongs to; 0 names none. */
    long activationToken();

    /**
     * The input-log offset of the item, so a crash re-run of the same input resolves to the worker
     * the first run spawned; -1 names none (a timer firing, a class without durable input).
     */
    long inputOffset();
}
