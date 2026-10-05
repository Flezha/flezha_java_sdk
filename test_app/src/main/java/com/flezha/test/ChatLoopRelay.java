package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code chat_loop_steps}: the durable-loop +
 * suspension shape. One iteration per inbox item — task TURN commits the turn to
 * Cell state and emits it, task REPLY emits the answer to the output. Between
 * turns the loop finds an empty inbox at a committed boundary and PARKS. The
 * turn {@code bye} ends the session.
 */
public final class ChatLoopRelay implements Relay {

    @Override
    public String factoryId() {
        return "chat_loop_steps";
    }

    @Override
    public Run run() {
        return Run.steps(Each.PER_INPUT);
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    /** The duration backstop keeps a stray e2e instance from outliving the test run. */
    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(ChatSteps.Turn.class)
                .named("mid")
                .into(ChatSteps.Reply.class)
                .output("out");
    }
}
