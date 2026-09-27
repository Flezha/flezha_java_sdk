package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** Pass-through entry tasklet of {@link MixedUnboundedOperator}. */
@Tasklet.Spec(name = "mixed_macro_entry_tasklet")
public class MixedMacroEntry implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit(input);
    }
}
