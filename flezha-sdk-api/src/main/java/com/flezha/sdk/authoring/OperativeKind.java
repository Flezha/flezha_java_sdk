package com.flezha.sdk.authoring;

/**
 * Which operative kind a {@link Procedure} with a {@code sink} generates.
 *
 * <p>A {@code sink} makes one procedure declaration a whole operative, and the kind is written
 * rather than inferred: a reader sees it on the declaration, and a stream is never recognised by a
 * missing word. See {@code docs/sdk/authoring_contract.md} §5.
 */
public enum OperativeKind {

    /** A continuous, stateful graph over a stream — {@code <Class>StreamOperative}. */
    STREAM,

    /** Ordered steps that run to completion — {@code <Class>WorkflowOperative}. */
    WORKFLOW
}
