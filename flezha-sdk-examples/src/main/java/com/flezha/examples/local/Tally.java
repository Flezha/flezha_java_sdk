package com.flezha.examples.local;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** The running count for one word. Each distinct word is its own cell. */
@Tasklet.Spec(name = "tally")
public final class Tally implements Tasklet<String, Count, String> {

    /** The word was seen once more. */
    public record Counted() implements Change<Count> {
        @Override
        public void applyTo(Count count) {
            count.seen++;
        }
    }

    @Override
    public void run(String word, Ctx<Count, String> ctx) throws TaskletException {
        ctx.change(new Counted());
        ctx.emit(word + " " + ctx.state().seen);
    }
}
