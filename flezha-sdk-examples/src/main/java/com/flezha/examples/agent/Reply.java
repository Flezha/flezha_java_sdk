package com.flezha.examples.agent;

import com.flezha.sdk.authoring.Procedure;

/** One turn of the conversation. */
@Procedure(id = "reply")
public final class Reply {

    public String run(String message, ConversationHandle conversation) {
        conversation.pushTurns("user: " + message);

        // A real agent calls a model here, with conversation.get().turns as the history. Keeping
        // the reply a function of that history is what lets a failed turn be re-run safely.
        String answer =
                "you said: " + message + " (turn " + conversation.get().turns.size() + ")";

        conversation.pushTurns("agent: " + answer);
        return answer;
    }
}
