package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code chat_loop_bounded}: the durable-loop +
 * suspension shape. One iteration per inbox item — task TURN commits the turn to
 * Cell state and emits it, task REPLY emits the answer to the sink. Between
 * turns the loop finds an empty inbox at a committed boundary and PARKS. The
 * turn {@code bye} ends the session.
 */
public final class ChatLoopBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "chat_loop_bounded";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
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
                .sink("out");
    }
}
