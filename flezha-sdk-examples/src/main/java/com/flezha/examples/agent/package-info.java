/**
 * A long-lived agent: one durable iteration per message, holding its history across restarts.
 *
 * <p>The conversation is kept in entity state, so the agent resumes mid-thread after a crash, a
 * redeployment, or an arbitrary period of silence. Nothing runs between messages; the instance
 * parks holding its journal and is resumed when addressed.
 *
 * <p>{@link com.flezha.examples.agent.ChatOperative} declares the two properties that make this a
 * loop rather than a single pass. {@link com.flezha.sdk.operative.LoopMode} of {@code PER_INPUT}
 * gives one durable iteration per inbound message. {@code wakeAfter} additionally schedules a wake
 * when no message has arrived, which is how an agent produces a nudge, a summary or a follow-up
 * without being addressed; omit it and the agent wakes only when spoken to.
 *
 * <p>{@link com.flezha.examples.agent.Reply} is where a real implementation would call a model,
 * passing the accumulated turns as history. Keeping the reply a function of that history is
 * what allows an iteration that failed part-way to be re-executed safely.
 *
 * <p>{@link com.flezha.examples.agent.Conversation} is a {@code @State} class, so each turn is
 * recorded by the generated {@code pushTurns} rather than added to the list directly, and the
 * processor also generates the Kryo type list, so nothing is registered by hand.
 */
package com.flezha.examples.agent;
