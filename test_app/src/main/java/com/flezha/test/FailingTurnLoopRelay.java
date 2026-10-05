package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code failing_turn_loop_steps}: a
 * keep-progress session whose turn can fail after {@code note} committed.
 */
public class FailingTurnLoopRelay implements Relay {

    @Override
    public String factoryId() {
        return "failing_turn_loop_steps";
    }

    @Override
    public Run run() {
        return new Run.Steps(Each.PER_INPUT, OnFailure.KEEP_PROGRESS);
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
        c.input(String.class, "in")
                .into(FailingTurnSteps.Note.class)
                .named("noted")
                .into(FailingTurnSteps.Answer.class)
                .output("answer");
    }
}
