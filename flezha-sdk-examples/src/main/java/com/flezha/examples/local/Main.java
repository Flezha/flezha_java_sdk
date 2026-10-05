package com.flezha.examples.local;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.flezha.sdk.LocalRelay;
import java.time.Duration;

/**
 * Executes the declared graph inside this JVM. No controller, socket or container is involved, and
 * nothing persists beyond the run.
 *
 * <p>{@code feed} places a message on a named entry channel, {@code onOutput} registers a callback
 * against an output channel, and {@code runUntilIdle} drives the graph until it drains or the
 * timeout elapses. The calling thread is the poll loop, so the graph makes no progress without it.
 */
public final class Main {

    public static void main(String[] args) {
        try (LocalRelay run = LocalRelay.start(WordCountRelay.class)) {
            run.onOutput("totals", out -> System.out.println("totals <- " + new String(out.payload(), UTF_8)));
            run.feed("lines", "the quick brown fox");
            run.feed("lines", "the lazy dog");
            run.runUntilIdle(Duration.ofSeconds(10));
        }
    }

    private Main() {}
}
