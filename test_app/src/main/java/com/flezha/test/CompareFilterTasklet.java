package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

@Tasklet.Spec(name = "compare_filter_tasklet")
public final class CompareFilterTasklet implements Tasklet<String, Void, String> {

    /** The value an input must equal to pass. */
    private static final String COMPARABLE = "default";

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        if (COMPARABLE.equals(input)) {
            ctx.emit(input);
        }
    }
}
