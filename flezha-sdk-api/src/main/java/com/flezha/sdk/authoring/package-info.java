/**
 * The annotations a procedure, its state and a short-form operative are written with.
 *
 * <p>{@link com.flezha.sdk.authoring.Procedure} marks a class with one public {@code run} method.
 * The annotation processor generates {@code <Class>Procedure} from it, the
 * {@link com.flezha.sdk.procedure.Procedure} implementation an operative wires, reading the input
 * and output types from the {@code run} signature so that nothing is declared twice.
 * {@link com.flezha.sdk.authoring.State} marks a state class, from which the processor generates the
 * change type and the handle a stateful {@code run} takes, and
 * {@link com.flezha.sdk.authoring.Journal} opts one of its map fields into per-key recording.
 *
 * <p>{@link com.flezha.sdk.authoring.EntitiesFor} marks the method that routes an input to the
 * static entities a procedure's {@code keys} name, and {@link com.flezha.sdk.authoring.InitState}
 * the method that builds an entity's initial state from its key.
 * {@link com.flezha.sdk.authoring.EntityKey} generates nothing: declaring it fails processing with
 * the instruction to key the stream on the wiring instead.
 *
 * <p>{@link com.flezha.sdk.authoring.StreamOperative} and
 * {@link com.flezha.sdk.authoring.WorkflowOperative} declare a whole single-procedure operative of
 * their kind, the short form. These two and {@code @Procedure} share their names with the
 * interfaces the generated classes implement.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.authoring;
