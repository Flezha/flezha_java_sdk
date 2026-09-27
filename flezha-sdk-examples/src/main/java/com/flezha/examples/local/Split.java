package com.flezha.examples.local;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** One line in, one emit per word out. */
@Tasklet.Spec(name = "split")
public final class Split implements Tasklet<String, Void, String> {

    @Override
    public void run(String line, Ctx<Void, String> ctx) throws TaskletException {
        for (String word : line.split("\\s+")) {
            if (!word.isEmpty()) {
                ctx.emit(word.toLowerCase());
            }
        }
    }
}
