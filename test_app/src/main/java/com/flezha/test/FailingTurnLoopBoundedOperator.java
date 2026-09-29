package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.RollbackMode;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code failing_turn_loop_bounded}: a
 * keep-progress session whose turn can fail after {@code note} committed.
 */
public class FailingTurnLoopBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "failing_turn_loop_bounded";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
    }

    @Override
    public RollbackMode rollbackMode() {
        return RollbackMode.KEEP_PROGRESS;
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
        c.input(String.class, "in")
                .into(FailingTurnSteps.Note.class)
                .named("noted")
                .into(FailingTurnSteps.Answer.class)
                .sink("answer");
    }
}
