package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code stateless_loop_steps}: the same durable
 * PER_INPUT loop as {@link ChatLoopRelay} minus its stateful first
 * tasklet. Nothing writes a Cell WAL, so the controller ships no
 * {@code RestoreInitState} on a rehydrating bind — the instance's task-journal
 * reaches the driver only if it rides the bind itself.
 */
public final class NoStateLoopRelay implements Relay {

    @Override
    public String factoryId() {
        return "stateless_loop_steps";
    }

    @Override
    public Run run() {
        return Run.steps(Each.PER_INPUT);
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(ChatSteps.Reply.class).output("out");
    }
}
