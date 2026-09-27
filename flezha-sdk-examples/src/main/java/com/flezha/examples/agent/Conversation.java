package com.flezha.examples.agent;

import com.flezha.sdk.state.Change;
import java.util.ArrayList;
import java.util.List;

/** The whole conversation. Being durable is what makes the agent resumable. */
public class Conversation {
    public List<String> turns = new ArrayList<>();

    /** One turn joins the conversation. Recorded, so a restart finds every turn again. */
    public record TurnTaken(String turn) implements Change<Conversation> {
        @Override
        public void applyTo(Conversation conversation) {
            conversation.turns.add(turn);
        }
    }
}
