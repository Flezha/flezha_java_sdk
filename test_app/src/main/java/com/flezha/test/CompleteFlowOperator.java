package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code complete_flow}: a flow class whose tasklet echoes each
 * input on {@code complete_flow_out} and ends its instance on {@code bye}, the echo of {@code bye}
 * included.
 */
public final class CompleteFlowOperator implements Operator {

    @Override
    public String factoryId() {
        return "complete_flow";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(Echo.class).sink("complete_flow_out");
    }

    @Tasklet.Spec(name = "complete_flow_tasklet")
    public static final class Echo implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if ("bye".equals(input)) {
                ctx.complete(input);
            }
            ctx.emit(input);
        }
    }
}
