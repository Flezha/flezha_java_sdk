package com.flezha.test;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import java.time.Duration;
import java.util.Optional;

/**
 * The chat-loop bench fixture ({@code mix bench.bounded --scenario turns}): a
 * durable {@link LoopMode.PER_INPUT} chain, one iteration per inbox turn, its
 * reply going to {@code bench_sink}. Mirrors the Rust {@code bench_loop_bounded}.
 */
public final class BenchLoopBoundedOperator implements BoundedOperator {
    @Override
    public String factoryId() {
        return "bench_loop_bounded";
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
