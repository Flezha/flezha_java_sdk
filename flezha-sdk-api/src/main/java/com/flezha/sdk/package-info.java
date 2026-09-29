/**
 * Entry points for declaring and running a Flezha application.
 *
 * <p>An application is built from two authoring types and started by a runner. A
 * {@link com.flezha.sdk.tasklet.Tasklet} contains the code that handles a single message. A
 * {@link com.flezha.sdk.operator.UnboundedOperator} or
 * {@link com.flezha.sdk.operator.BoundedOperator} declares the graph that connects entry
 * channels, tasklets and sinks, and is the unit the cluster schedules, restarts and relocates.
 * {@link com.flezha.sdk.Flezha#createRunner(String)} returns a {@link com.flezha.sdk.FlezhaRunner}
 * builder on which every operator class is declared before the runner is started.
 *
 * <p>A runner connects to a controller, which owns placement, durability, delivery guarantees,
 * retries and reconnection. {@code run()} connects and blocks until shutdown; {@code start()}
 * returns to the caller and leaves the runner to be closed explicitly.
 *
 * <p>{@link com.flezha.sdk.LocalOperator} executes the same declared graph inside the calling JVM
 * with no controller, no socket and no container. Messages are supplied with {@code feed} and sink
 * output is delivered to a callback registered with {@code onSink}. Nothing is durable in this
 * mode: state is held in memory, no write-ahead log or snapshot is written, and no recovery is
 * possible. Declarations that require a controller, including durability, effectively-once
 * delivery, plugins, placement and the dead-letter queue, are refused at startup and the refusal
 * names the declaration responsible.
 *
 * <p>The remaining types in this package are the supporting surface for those two paths:
 * {@link com.flezha.sdk.SinkOutput} is the frame handed to a local-mode sink callback, and
 * {@link com.flezha.sdk.FlezhaException} is the one exception thrown for anything Flezha refuses,
 * carrying a stable {@code code} to branch on.
 *
 * <p>{@link com.flezha.sdk.Logger} is where the SDK's own diagnostics go. The default,
 * {@link com.flezha.sdk.ConsoleLogger}, writes to standard error at the
 * {@link com.flezha.sdk.LogLevel} {@code FLEZHA_LOG_LEVEL} names, or {@code warn};
 * {@code FlezhaRunner.logger(Logger)} replaces it with the host's own, in one lambda.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk;
