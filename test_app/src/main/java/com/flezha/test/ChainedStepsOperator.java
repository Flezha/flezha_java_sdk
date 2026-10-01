package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code chained_steps}: a durable chained loop whose pass prints
 * {@code chained_pass <n>} and hands the next pass {@code n - 1} on its own channel; the pass at
 * zero hands nothing, and the empty batch ends the instance.
 */
public final class ChainedStepsOperator implements Operator {

    @Override
    public String factoryId() {
        return "chained_steps";
    }

    @Override
    public Run run() {
        return Run.steps(Each.CHAINED);
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(Countdown.class).named("carry");
    }

    @Tasklet.Spec(name = "chained_countdown")
    public static final class Countdown extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            System.out.println("chained_pass " + input);
            System.out.flush();
            int n = Integer.parseInt(input);
            if (n > 0) {
                ctx.emit(Integer.toString(n - 1));
            }
        }
    }
}
