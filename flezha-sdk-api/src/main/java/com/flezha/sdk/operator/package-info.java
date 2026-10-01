/**
 * The operator: the deployable unit, and the settings tree that says how it runs and where its
 * work is kept.
 *
 * <p>A tasklet cannot be deployed on its own, because nothing in the system knows where its
 * messages originate or where its output belongs. An operator supplies that binding, which makes
 * it the unit the controller schedules onto a host, restarts after a failure, relocates when a host
 * is withdrawn, and scales across several hosts.
 *
 * <p>{@link com.flezha.sdk.operator.Operator} is the one authoring base. Its members are the stable
 * {@code factoryId()}, {@code channels(ChannelBuilder)}, where the graph of tasklets is declared,
 * and the settings tree: {@code run()} is a {@link com.flezha.sdk.operator.Operator.Run.Flow flow},
 * where messages flow continuously through the graph and each cell holds its state between them,
 * or {@link com.flezha.sdk.operator.Operator.Run.Steps steps}, where the graph runs stage by stage,
 * each stage committed at its boundary so a crash resumes at the stage that had not committed;
 * {@code durable()} is {@link com.flezha.sdk.operator.Operator.Durable.Memory memory},
 * {@link com.flezha.sdk.operator.Operator.Durable.Local local} or
 * {@link com.flezha.sdk.operator.Operator.Durable.Remote remote}. The graph and the settings are read
 * once at registration and validated as a whole; a refusal raises
 * {@link com.flezha.sdk.FlezhaException} with the refusal's {@code topology.*} code.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.operator;
