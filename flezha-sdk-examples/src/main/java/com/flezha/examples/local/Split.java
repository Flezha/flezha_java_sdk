package com.flezha.examples.local;

import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;

/** One line in, one emit per word out. */
@Tasklet(id = "split", emits = String.class)
public final class Split {

    public void run(String line, TaskletCtx ctx) throws TaskletException {
        for (String word : line.split("\\s+")) {
            if (!word.isEmpty()) {
                ctx.emit(word.toLowerCase());
            }
        }
    }
}
