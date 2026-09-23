/**
 * The two operative kinds and the base they share. An operative is the deployable unit.
 *
 * <p>A tasklet cannot be deployed on its own, because nothing in the system knows where its
 * messages originate or where its output belongs. An operative supplies that binding, which makes
 * it the unit the controller schedules onto a host, restarts after a failure, relocates when a host
 * is withdrawn, and scales across several hosts.
 *
 * <p>{@link com.flezha.sdk.operative.Operative} is the shared base. Its members are the stable
 * {@code factoryId()} that both kinds carry and {@code channels(ChannelBuilder)}, where both kinds
 * declare their graph of tasklets on the same builder. The graph is invoked once at registration
 * and validated as a whole; a declaration that is inconsistent or that requests an unavailable
 * capability is refused there, naming the declaration at fault. The two kinds differ in how the
 * graph runs and in the settings each carries beyond it.
 *
 * <p>{@link com.flezha.sdk.operative.StreamOperative} runs its graph continuously, holds per-cell
 * state, and is the right choice when work is expressed as messages flowing through one or more
 * tasklets. A refusal of its graph raises
 * {@link com.flezha.sdk.operative.StreamOperativeConfigurationException}.
 *
 * <p>{@link com.flezha.sdk.operative.WorkflowOperative} runs its graph to completion, for work that
 * must not be re-executed as a unit after a partial failure, such as a transaction spanning
 * external systems. Each stage of the graph is committed at its boundary, so a crash resumes at
 * the stage that had not committed rather than the first, and the value a tasklet hands to the
 * next stage is written down there, encoded by a codec the runtime derives, which no channel
 * declares. {@link com.flezha.sdk.operative.RollbackMode} selects whether an exhausted retry ladder
 * unwinds the committed stages through their tasklets' {@code compensate} methods or retains
 * progress. {@link com.flezha.sdk.operative.LoopMode} turns a workflow into a long-lived loop: one
 * durable iteration per inbound message or timer firing, parking between iterations while holding
 * its journal, and woken by the next message or the earliest timer its tasklets registered.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.operative;
