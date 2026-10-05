package com.flezha.test;

import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.channel.ChannelBuilder;
import java.time.Duration;
import java.util.Optional;

/**
 * The chat-loop bench fixture ({@code mix bench.steps --scenario turns}): a
 * durable {@link Each.PER_INPUT} chain, one iteration per inbox turn, its
 * reply going to {@code bench_sink}. Mirrors the Rust {@code bench_loop_steps}.
 */
public final class BenchLoopRelay implements Relay {
    @Override
    public String factoryId() {
        return "bench_loop_steps";
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
        return Optional.of(Duration.ofHours(24));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(BenchSteps.Turn.class)
                .named("mid")
                .into(ChatSteps.Reply.class)
                .sink("out");
    }
}
