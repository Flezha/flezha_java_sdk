package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code ResumeProbeRelay}: a stateful counter
 * that EMITS its running total on every input, so a Kafka plugin on the output
 * makes the persisted value externally observable. {@code chaos/tests/stateful-resume.sh}
 * uses it to prove WAL-backed state survives a hard controller kill / runner
 * restart. Input is a decimal delta string; output is the new running total as
 * ASCII bytes.
 */
public final class ResumeProbeRelay implements Relay {

    @Override
    public String factoryId() {
        return "resume_probe";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(ResumeProbeTasklet.class).sink("total");
    }

    @Tasklet.Spec(name = "resume_probe_tasklet")
    public static final class ResumeProbeTasklet implements Tasklet<String, ResumeProbeTasklet.State, byte[]> {

        /** The running total; a new Cell starts at zero and replaying its changes rebuilds it. */
        public static final class State {
            public long total;
        }

        /** A delta added to the running total. */
        public record Added(long delta) implements Change<State> {
            @Override
            public void applyTo(State state) {
                state.total += delta;
            }
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, Added.class);
        }

        @Override
        public void run(String input, Ctx<State, byte[]> ctx) throws TaskletException {
            long delta;
            try {
                delta = Long.parseLong(input);
            } catch (NumberFormatException e) {
                delta = 0L;
            }
            long newTotal = ctx.state().total + delta;
            ctx.change(new Added(delta));
            ctx.emit(Long.toString(newTotal).getBytes(StandardCharsets.UTF_8));
        }
    }
}
