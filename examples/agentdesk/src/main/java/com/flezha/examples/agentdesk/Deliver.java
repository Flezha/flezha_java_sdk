package com.flezha.examples.agentdesk;

import com.flezha.examples.agentdesk.Reply.Answer;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/** Hands the finished answer to the caller: a channel has one end, and {@code answer} is a sink. */
@Tasklet.Spec(name = "deliver")
public final class Deliver implements Tasklet<Answer, Void, Answer> {

    @Override
    public void run(Answer answer, Ctx<Void, Answer> ctx) throws TaskletException {
        // The whole answer, to whoever called.
        ctx.emit(answer);
    }

    // The undo, when a turn is taken back: an answer already given has nothing to take back.
    @Override
    public void compensate(List<Answer> sent, Ctx<Void, Answer> ctx) {}
}
