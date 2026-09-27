package com.flezha.examples.agent;

import com.flezha.examples.agent.Conversation.TurnTaken;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** One turn of the conversation. */
@Tasklet.Spec(name = "reply")
public final class Reply implements Tasklet<String, Conversation, String> {

    @Override
    public void run(String message, Ctx<Conversation, String> ctx) throws TaskletException {
        ctx.change(new TurnTaken("user: " + message));

        // A real agent calls a model here, with ctx.state().turns as the history. Keeping the
        // reply a function of that history is what lets a failed turn be re-run safely.
        String answer = "you said: " + message + " (turn " + ctx.state().turns.size() + ")";

        ctx.change(new TurnTaken("agent: " + answer));
        ctx.emit(answer);
    }
}
