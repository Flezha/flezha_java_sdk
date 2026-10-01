package com.flezha.examples.agentdesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import java.time.Duration;

/**
 * One conversation: a steps operator that runs one turn per message it is sent, for as long as
 * the session lasts. Every session is an instance of its own, with its own state.
 */
public final class AgentOperator implements Operator {

    @Override
    public String factoryId() {
        return "agent";
    }

    @Override
    public Run run() {
        return new Run.Steps(
                Each.PER_INPUT,                          // one turn per message
                OnFailure.KEEP_PROGRESS);                // a failed turn fails alone
    }

    @Override
    public Durable durable() {
        return new Durable.Local(Duration.ofSeconds(10)); // an idle session sleeps after 10 s
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "turn")
                .into(Reply.class)
                .sink("answer")                          // to whoever listens
                .into(Deliver.class)
                .response();                             // to whoever called
        c.input(Message.class, "message")                // from the gateway
                .map(String.class, Message::text)
                .into(Reply.class);
    }
}
