package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors of the Rust test app's part fixtures. For an input {@code n} the tasklet sends the parts
 * {@code "1"} to {@code "<n>"} and then emits {@code done:<n>} on its output {@code answer}; for
 * {@code retry:<n>} it sends the same parts and fails retryably the first time it sees that input,
 * so the second attempt's parts number from 0 again.
 */
public final class PartRelays {

    private PartRelays() {}

    /** The flow relay. */
    public static final class PartEcho implements Relay {
        @Override
        public String factoryId() {
            return "part_echo";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").retryBackoffMs(25).into(Echo.class).output("answer");
        }
    }

    /** The same per message, as the stage of a {@code PER_INPUT} steps relay. */
    public static final class PartEchoSteps implements Relay {
        @Override
        public String factoryId() {
            return "part_echo_steps";
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
            c.input(String.class, "in").retryBackoffMs(25).into(StepsEcho.class).output("answer");
        }
    }

    /** The {@code retry:} inputs already failed once in this process. */
    private static final Set<String> FAILED_ONCE = ConcurrentHashMap.newKeySet();

    @Tasklet.Spec(name = "part_echo")
    public static final class Echo extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            partsThenAnswer(input, ctx);
        }
    }

    @Tasklet.Spec(name = "part_echo_turn")
    public static final class StepsEcho extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            partsThenAnswer(input, ctx);
        }
    }

    private static void partsThenAnswer(String input, Ctx<Void, String> ctx) throws TaskletException {
        boolean retry = input.startsWith("retry:");
        int count;
        try {
            count = Integer.parseInt(retry ? input.substring("retry:".length()) : input);
        } catch (NumberFormatException e) {
            throw new TaskletException.NonRetryable("not a part count: " + input);
        }
        for (int part = 1; part <= count; part++) {
            ctx.part(Integer.toString(part));
        }
        if (retry && FAILED_ONCE.add(input)) {
            throw new TaskletException("first attempt of " + input);
        }
        ctx.emit("done:" + count);
    }
}
