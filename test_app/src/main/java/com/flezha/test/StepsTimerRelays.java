package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@code timer_loop_steps} and {@code event_timer_steps}, the Java mirror of the Rust test
 * app's timers inside a steps relay. {@code tick} arms a processing-time timer of the delay
 * {@code after:<ms>} names and emits {@code armed:<at>}; its firing emits {@code fired:<at>}
 * and opens a pass of its own, which {@code tock} prefixes onto the sink. {@code slow:<ms>}
 * holds {@code tick} that long. On a runner booted with {@code FLEZHA_STEPS_E2E_HOLD} the firing
 * parks at the gate: {@code timer} inside {@code onTimer}, before {@code tick} commits; {@code tock}
 * in {@code tock}, after. {@code event_tick}'s clock is the message's leading event time:
 * each message arms an event-time timer 100 later and emits {@code seen:<t>}.
 */
public final class StepsTimerRelays {

    private StepsTimerRelays() {}

    public static final class TimerLoop implements Relay {
        @Override
        public String factoryId() {
            return "timer_loop_steps";
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
            c.input(String.class, "in").into(Tick.class).named("ticked").into(Tock.class).sink("out");
        }
    }

    public static final class EventTimer implements Relay {
        @Override
        public String factoryId() {
            return "event_timer_steps";
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
            c.input(String.class, "in").into(EventTick.class).sink("out");
        }
    }

    @Tasklet.Spec(name = "tick")
    public static final class Tick implements Tasklet<String, SeenState, String> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Insert.class, SeenState.class);
        }

        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            if (input.startsWith("after:")) {
                long at = ctx.timerAfter(Duration.ofMillis(millis(input, "after:")));
                ctx.change(new Insert("armed:" + at));
                ctx.emit("armed:" + at);
                return;
            }
            if (input.startsWith("slow:")) {
                try {
                    Thread.sleep(millis(input, "slow:"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ctx.emit(input);
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<SeenState, String> ctx) throws TaskletException {
            StepsE2eGate.holdAt("timer");
            ctx.emit("fired:" + firedAt);
        }

        private static long millis(String input, String prefix) {
            try {
                return Long.parseLong(input.substring(prefix.length()));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    @Tasklet.Spec(name = "tock")
    public static final class Tock extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (input.startsWith("fired:")) {
                StepsE2eGate.holdAt("tock");
            }
            ctx.emit("tock:" + input);
        }
    }

    @Tasklet.Spec(name = "event_tick")
    public static final class EventTick implements Tasklet<String, SeenState, String> {
        @Override
        public long eventTime(String input) {
            int at = input.indexOf(':');
            try {
                return Long.parseLong(at < 0 ? input : input.substring(0, at));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Insert.class, SeenState.class);
        }

        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            long at = ctx.timerAtEventTime(ctx.now() + 100);
            ctx.change(new Insert("armed:" + at));
            ctx.emit("seen:" + ctx.now());
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("fired:" + firedAt);
        }
    }
}
