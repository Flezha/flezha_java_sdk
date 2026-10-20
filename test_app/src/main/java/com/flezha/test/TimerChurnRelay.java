package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Mirror of the Rust runner's {@code TimerChurnRelay}, for {@code mix bench --shape timer}:
 * each input arms a processing-time timer one millisecond ahead, cancels the one the previous
 * input armed and echoes; a firing emits {@code fired:<at>}, which the bench does not count.
 */
public final class TimerChurnRelay implements Relay {

    @Override
    public String factoryId() {
        return "timer_churn";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(Task.class).output("echo");
    }

    public static final class Last {
        public long at;
    }

    public static final class Armed implements Change<Last> {
        public long at;

        public Armed() {}

        Armed(long at) {
            this.at = at;
        }

        @Override
        public void applyTo(Last state) {
            state.at = at;
        }
    }

    @Tasklet.Spec(name = "timer_churn_tasklet")
    public static final class Task implements Tasklet<String, Last, byte[]> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Last.class, Armed.class);
        }

        @Override
        public void run(String input, Ctx<Last, byte[]> ctx) throws TaskletException {
            long at = ctx.timerAfter(Duration.ofMillis(1));
            long last = ctx.state().at;
            if (last != 0 && last != at) {
                ctx.cancelProcessingTimeTimer(last);
            }
            ctx.change(new Armed(at));
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<Last, byte[]> ctx) throws TaskletException {
            ctx.emit(("fired:" + firedAt).getBytes(StandardCharsets.UTF_8));
        }
    }
}
