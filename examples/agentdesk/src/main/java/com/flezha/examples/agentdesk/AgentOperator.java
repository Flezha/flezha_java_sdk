package com.flezha.examples.agentdesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.RollbackMode;

/**
 * One conversation: a bounded operator that runs one turn per message it is sent, for as long as
 * the session lasts. Every session is an instance of its own, with its own state.
 */
public final class AgentOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "agent";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;                       // one turn per message
    }

    @Override
    public RollbackMode rollbackMode() {
        return RollbackMode.KEEP_PROGRESS;               // a failed turn fails alone
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "turn")
                .into(Reply.class)
                .sink("answer")                          // to whoever listens
                .into(Deliver.class)
                .response();                             // to whoever called
    }
}
