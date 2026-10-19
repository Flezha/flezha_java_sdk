package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.CapturedStderr;
import com.flezha.sdk.ConsoleLogger;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.LocalOutput;
import com.flezha.sdk.LocalRelay;
import com.flezha.sdk.Logger;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ctx.effect} through the real core in local mode, where no input takes an input-log offset:
 * nothing can re-run an input, so nothing is kept and the function runs each time. The core still
 * refuses what it refuses everywhere.
 */
class LocalModeEffectTest {

    private static final Duration BUDGET = Duration.ofSeconds(20);

    static final AtomicInteger RUNS = new AtomicInteger();

    /** Each run logs the core's local-mode banner, and nothing else may be logged. */
    private static CapturedStderr stderr;

    @BeforeAll
    static void captureStderr() {
        stderr = CapturedStderr.start();
    }

    @AfterAll
    static void onlyBannersWereLogged() {
        stderr.close();
        assertThat(stderr.lines()).allMatch(line -> line.startsWith("[flezha] LOCAL MODE: "));
    }

    @Test
    @DisplayName("the core's local-mode banner reaches the runner's logger at error, which no level hides")
    void theBannerReachesTheRunnersLogger() {
        List<String> logged = new CopyOnWriteArrayList<>();
        Flezha.createRunner("notices")
                .logger((level, format, args) -> logged.add(level + " " + Logger.render(format, args)));
        try {
            release(AsksRelay.class, List.of("EURUSD"));
        } finally {
            Flezha.createRunner("notices").logger(ConsoleLogger.fromEnv());
        }
        assertThat(logged).anyMatch(line -> line.startsWith("ERROR LOCAL MODE: state is held in memory"));
    }

    @Test
    @DisplayName("local mode runs the function every time")
    void localModeRunsTheFunctionEveryTime() {
        RUNS.set(0);

        List<String> released = release(AsksRelay.class, List.of("EURUSD", "EURUSD"));

        assertThat(RUNS).hasValue(2);
        assertThat(released).containsExactly("EURUSD#1", "EURUSD#2");
    }

    @Test
    @DisplayName("the core refuses a bad name and a nested effect, without a retry")
    void theCoreRefusesABadNameAndANestedEffect() {
        List<String> released = release(RefusedRelay.class, List.of("", "m".repeat(256), "nested"));

        assertThat(released).containsExactly("effect.bad_name", "effect.bad_name", "effect.nested");
    }

    private static List<String> release(Class<?> relay, List<String> inputs) {
        ConcurrentLinkedQueue<LocalOutput> released = new ConcurrentLinkedQueue<>();
        try (LocalRelay run = LocalRelay.start(relay)) {
            run.onOutput("answer", released::add);
            inputs.forEach(input -> run.feed("in", input));
            run.runUntilIdle(BUDGET);
        }
        return released.stream()
                .map(output -> new String(output.payload(), StandardCharsets.UTF_8))
                .toList();
    }

    /** The code of the refusal a non-retryable effect error carries. */
    private static String code(TaskletException refused) {
        assertThat(refused).isInstanceOf(TaskletException.NonRetryable.class);
        return ((FlezhaException) refused.getCause()).code();
    }

    @Tasklet.Spec(name = "effect_asks")
    public static final class Asks implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(ctx.effect("model", String.class, () -> input + "#" + RUNS.incrementAndGet()));
        }
    }

    public static final class AsksRelay implements Relay {
        @Override
        public String factoryId() {
            return "effect_asks_local";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Asks.class).output("answer");
        }
    }

    /** Releases the code each call was refused with: an empty or long name, or {@code nested}. */
    @Tasklet.Spec(name = "effect_refused")
    public static final class Refused implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (input.equals("nested")) {
                ctx.emit(ctx.effect("outer", String.class, () -> {
                    try {
                        return ctx.effect("inner", String.class, () -> "ran");
                    } catch (TaskletException refused) {
                        return code(refused);
                    }
                }));
                return;
            }
            try {
                ctx.effect(input, String.class, () -> {
                    throw new AssertionError("a refused call runs nothing");
                });
            } catch (TaskletException refused) {
                ctx.emit(code(refused));
            }
        }
    }

    public static final class RefusedRelay implements Relay {
        @Override
        public String factoryId() {
            return "effect_refused_local";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Refused.class).output("answer");
        }
    }
}
