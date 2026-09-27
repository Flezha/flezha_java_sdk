/**
 * A long-lived agent: one durable iteration per message, holding its history across restarts.
 *
 * <p>The conversation is kept in cell state, so the agent resumes mid-thread after a crash, a
 * redeployment, or an arbitrary period of silence. Nothing runs between messages; the instance
 * parks holding its journal and is resumed when addressed.
 *
 * <p>{@link com.flezha.examples.agent.ChatOperator} declares the two properties that make this a
 * loop rather than a single pass. {@link com.flezha.sdk.operator.LoopMode} of {@code PER_INPUT}
 * gives one durable iteration per inbound message. An agent that should also act without being
 * addressed, for a nudge, a summary or a follow-up, has a tasklet register a timer with
 * {@code ctx.timerAfter} and handle it in {@code onTimer}: the sleeping instance wakes at it.
 *
 * <p>{@link com.flezha.examples.agent.Reply} is where a real implementation would call a model,
 * passing the accumulated turns as history. Keeping the reply a function of that history is
 * what allows an iteration that failed part-way to be re-executed safely.
 *
 * <p>{@link com.flezha.examples.agent.Conversation} is the state, and its nested
 * {@code TurnTaken} record is the one change it records: each turn goes through
 * {@code ctx.change} rather than being added to the list directly, so a restart replays every
 * turn. Nothing is registered by hand; the SDK finds the state class and the change nested in it.
 */
package com.flezha.examples.agent;
