package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@code timer_loop_bounded} and {@code event_timer_bounded}, the Java mirror of the Rust test
 * app's timers inside a bounded operator. {@code tick} arms a processing-time timer of the delay
 * {@code after:<ms>} names and emits {@code armed:<at>}; its firing emits {@code fired:<at>}
 * and opens a pass of its own, which {@code relay} prefixes onto the sink. {@code slow:<ms>}
 * holds {@code tick} that long. On a runner booted with {@code FLEZHA_BOUNDED_E2E_HOLD} the firing
 * parks at the gate: {@code timer} inside {@code onTimer}, before {@code tick} commits; {@code relay}
 * in {@code relay}, after. {@code event_tick}'s clock is the message's leading event time:
 * each message arms an event-time timer 100 later and emits {@code seen:<t>}.
 */
public final class BoundedTimerOperators {

    private BoundedTimerOperators() {}

    public static final class TimerLoop implements BoundedOperator {
        @Override
        public String factoryId() {
            return "timer_loop_bounded";
        }

        @Override
        public LoopMode loopMode() {
            return LoopMode.PER_INPUT;
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(600));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Tick.class).named("ticked").into(Relay.class).sink("out");
        }
    }

    public static final class EventTimer implements BoundedOperator {
        @Override
        public String factoryId() {
            return "event_timer_bounded";
        }

        @Override
        public LoopMode loopMode() {
            return LoopMode.PER_INPUT;
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

    public static final class Tick implements Tasklet<String, SeenState, String, Insert, WallClock, Void> {
        @Override
        public String name() {
            return "tick";
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Insert.class, SeenState.class);
        }

        @Override
        public SeenState initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new SeenState();
        }

        @Override
        public void applyChange(SeenState state, Insert change) {
            state.items.add(change.key());
        }

        @Override
        public void run(String input, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            if (input.startsWith("after:")) {
                long at = ctx.timerAfter(Duration.ofMillis(millis(input, "after:")));
                state.apply(new Insert("armed:" + at));
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
        public void onTimer(long firedAt, TimerKind kind, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            BoundedE2eGate.holdAt("timer");
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

    public static final class Relay extends StatelessStep {
        @Override
        public String name() {
            return "relay";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
            if (input.startsWith("fired:")) {
                BoundedE2eGate.holdAt("relay");
            }
            ctx.emit("relay:" + input);
        }
    }

    public static final class EventTick implements Tasklet<String, SeenState, String, Insert, EventTimeFrom, Void> {
        @Override
        public String name() {
            return "event_tick";
        }

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
        public SeenState initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new SeenState();
        }

        @Override
        public void applyChange(SeenState state, Insert change) {
            state.items.add(change.key());
        }

        @Override
        public void run(String input, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            long at = ctx.timerAtEventTime(ctx.now() + 100);
            state.apply(new Insert("armed:" + at));
            ctx.emit("seen:" + ctx.now());
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit("fired:" + firedAt);
        }
    }
}
