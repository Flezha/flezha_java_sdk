package com.flezha.test;

import com.flezha.sdk.authoring.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** Single-tasklet stateful short-form twin of {@link StoreInputTasklet}. */
@Relay(id = "sugar_store_input", output = "sugar_store_sink")
public class SugarStore implements Tasklet<String, SugarStoreState, String> {

    /** One text input to append. */
    public record Stored(String text) implements Change<SugarStoreState> {
        @Override
        public void applyTo(SugarStoreState state) {
            state.text_inputs.add(text);
        }
    }

    @Override
    public void run(String input, Ctx<SugarStoreState, String> ctx) throws TaskletException {
        ctx.change(new Stored(input));
        ctx.emit(input);
    }
}
