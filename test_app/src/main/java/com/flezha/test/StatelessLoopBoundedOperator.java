package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code stateless_loop_bounded}: the same durable
 * PER_INPUT loop as {@link ChatLoopBoundedOperator} minus its stateful first
 * tasklet. Nothing writes a Cell WAL, so the controller ships no
 * {@code RestoreInitState} on a rehydrating bind — the instance's task-journal
 * reaches the driver only if it rides the bind itself.
 */
public final class StatelessLoopBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "stateless_loop_bounded";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
    }

    @Override
    public boolean selfTerminating() {
        return false;
    }

    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(ChatSteps.Reply.class).sink("out");
    }
}
