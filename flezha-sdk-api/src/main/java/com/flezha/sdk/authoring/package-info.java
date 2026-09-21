/**
 * The annotations a tasklet, its state and a short-form operative are written with.
 *
 * <p>{@link com.flezha.sdk.authoring.Tasklet} marks a class with one public {@code run} method.
 * The annotation processor generates {@code <Class>Tasklet} from it, the
 * {@link com.flezha.sdk.tasklet.Tasklet} implementation an operative wires, reading the input
 * and output types from the {@code run} signature so that nothing is declared twice.
 * {@link com.flezha.sdk.authoring.State} marks a state class, from which the processor generates the
 * change type and the handle a stateful {@code run} takes, and
 * {@link com.flezha.sdk.authoring.Journal} opts one of its map fields into per-key recording.
 *
 * <p>{@link com.flezha.sdk.authoring.CellsFor} marks the method that routes an input to the
 * static cells a tasklet's {@code keys} name, and {@link com.flezha.sdk.authoring.InitState}
 * the method that builds a cell's initial state from its key.
 * {@link com.flezha.sdk.authoring.CellKey} generates nothing: declaring it fails processing with
 * the instruction to key the stream on the wiring instead.
 *
 * <p>{@link com.flezha.sdk.authoring.StreamOperative} and
 * {@link com.flezha.sdk.authoring.WorkflowOperative} declare a whole single-tasklet operative of
 * their kind, the short form. These two and {@code @Tasklet} share their names with the
 * interfaces the generated classes implement.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.authoring;
