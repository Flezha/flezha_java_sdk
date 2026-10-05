package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code chat_rewind_steps}: the same chat loop,
 * but its replying tasklet can be undone. Unwinding in reverse iteration order
 * retracts the turns in reverse.
 */
public final class ChatRewindRelay implements Relay {

    @Override
    public String factoryId() {
        return "chat_rewind_steps";
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
        c.input(String.class, "in")
                .into(ChatSteps.Turn.class)
                .named("mid")
                .into(ChatSteps.ReplyUndoable.class)
                .sink("out");
    }
}
