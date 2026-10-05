package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Run-once steps relay mirroring {@code rust_runner}'s
 * {@code echo_steps}: its one tasklet emits {@code echo:<message>} to the
 * sink once for the first message the instance receives, the pass ends, and
 * the controller deregisters it.
 */
public final class EchoRelay implements Relay {

    @Override
    public String factoryId() {
        return "echo_steps";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(Echo.class).sink("out");
    }

    /** Echoes the message a {@code ONCE} steps relay's single pass runs over. */
    @Tasklet.Spec(name = "echo")
    public static final class Echo extends NoStateStep {
        @Override
        public void run(String args, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("echo:" + args);
        }
    }
}
