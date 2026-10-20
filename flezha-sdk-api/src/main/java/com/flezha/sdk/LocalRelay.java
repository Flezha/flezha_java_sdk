package com.flezha.sdk;

import com.flezha.sdk.internal.LocalRunHandle;
import com.flezha.sdk.internal.RuntimeBridge;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Run a declared relay graph in this process, with no controller.
 *
 * <p>{@link #start} takes the relay class you deploy and runs the
 * {@code channels()} graph it already declares — no local-mode member on the
 * class, no second declaration. What runs is the deployed machinery: entry
 * decode, edge transforms and keying, one Cell per resolved key with its
 * own mailbox, the retry ladder, and inter-tasklet delivery performed by
 * the runtime.
 *
 * <pre>{@code
 * try (LocalRelay run = LocalRelay.start(WordCount.class)) {
 *     run.onOutput("totals", out -> System.out.println(new String(out.payload())));
 *     run.feed("lines", "the quick brown fox");
 *     run.runUntilIdle(Duration.ofSeconds(5));
 * }
 * }</pre>
 *
 * <p><b>State is held in memory and nothing is durable.</b> Durability,
 * plugins, placement and migration, the dead-letter queue, observability
 * export, authz and multi-runner scale all need a controller; the run says so
 * through the SDK's logger every time it starts. The "Local mode" section of
 * https://github.com/flezha/flezha/blob/main/docs/public/topics/core/running_and_testing.md#local-mode
 * walks through it.
 */
public final class LocalRelay implements AutoCloseable {

    private static final String IMPL = "com.flezha.sdk.runtime.LocalImpl";

    private final LocalRunHandle handle;

    private LocalRelay(LocalRunHandle handle) {
        this.handle = handle;
    }

    /**
     * Register {@code type}'s declared graph and start it locally. {@code type} is an
     * relay class, or a tasklet class carrying the {@code @ShortRelay} short form.
     *
     * @throws RuntimeException naming the capability when the declaration
     *     needs a controller
     */
    @Nonnull
    public static LocalRelay start(Class<?> type) {
        return new LocalRelay((LocalRunHandle)
                RuntimeBridge.invoke(IMPL, "start", new Class<?>[] {Class.class}, "Failed to start a local run", type));
    }

    /**
     * Deliver every value released on the output {@code channel} to
     * {@code callback}, in release order. An output channel takes the producing
     * tasklet's name unless the declaration {@code named} it.
     */
    @Nonnull
    public LocalRelay onOutput(String channel, Consumer<LocalOutput> callback) {
        handle.onOutput(channel, callback);
        return this;
    }

    /** Feed one value's wire bytes to a declared entry channel. */
    @Nonnull
    public LocalRelay feed(String channel, byte[] payload) {
        handle.feed(channel, payload);
        return this;
    }

    /** {@link #feed(String, byte[])} with the text's UTF-8 bytes. */
    @Nonnull
    public LocalRelay feed(String channel, String payload) {
        return feed(channel, payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Block until the graph has nothing left to do.
     *
     * @throws RuntimeException when {@code budget} elapses first — a timeout
     *     is reported, never passed off as done
     */
    public void runUntilIdle(Duration budget) {
        handle.runUntilIdle(budget);
    }

    @Override
    public void close() {
        handle.close();
    }
}
