package com.flezha.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.runtime.RepositoryFixture;
import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The harness's timers: set by a run, pending until fired, fired through {@code onTimer}. */
class HarnessTimersTest {

    /**
     * An input {@code t} sets an event-time timer at {@code t + 10} and a processing-time one five
     * milliseconds after now; a negative input cancels the event-time timer at its absolute value.
     * The firing at 110 sets a further event-time timer at 115.
     */
    @Tasklet.Spec(name = "deadline")
    public static final class DeadlineTasklet implements Tasklet<Long, Void, String> {
        @Override
        public long eventTime(Long input) {
            return input;
        }

        @Override
        public void run(Long input, Ctx<Void, String> ctx) throws TaskletException {
            if (input < 0) {
                ctx.cancelTimer(TimerKind.EVENT_TIME, -input);
                return;
            }
            ctx.timerAtEventTime(input + 10);
            ctx.timerAfter(Duration.ofMillis(5));
        }

        @Override
        public void onTimer(TimerFiring firing, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(firing.kind().label() + "@" + firing.scheduledAt() + " now=" + ctx.now());
            if (firing.scheduledAt() == 110) {
                ctx.timerAtEventTime(115);
            }
        }
    }

    /** Wall clock: an event-time timer is refused, and the refusal's code is emitted. */
    @Tasklet.Spec(name = "wall_deadline")
    public static final class WallDeadlineTasklet implements Tasklet<Long, Void, String> {
        @Override
        public void run(Long input, Ctx<Void, String> ctx) throws TaskletException {
            try {
                ctx.timerAtEventTime(input);
            } catch (FlezhaException refused) {
                ctx.emit(refused.code());
            }
        }
    }

    @Test
    @DisplayName("a run's timers are pending, event-time first, each kind ascending")
    void aRunsTimersArePending() {
        TaskletTestHarness<Long, Void, String> harness =
                TaskletTestHarness.of(new DeadlineTasklet()).applyInputs(List.of(100L));
        assertThat(harness.pendingTimers())
                .containsExactly(
                        new TimerFiring(TimerKind.EVENT_TIME, 110), new TimerFiring(TimerKind.PROCESSING_TIME, 105));
    }

    @Test
    @DisplayName("fireTimer runs onTimer at the scheduled time and removes the timer")
    void fireTimerRunsOnTimer() {
        TaskletTestHarness<Long, Void, String> harness = TaskletTestHarness.of(new DeadlineTasklet())
                .applyInputs(List.of(100L))
                .fireTimer(TimerKind.PROCESSING_TIME, 105);
        assertThat(harness.recordedEmits()).containsExactly("processing_time@105 now=105");
        assertThat(harness.pendingTimers()).containsExactly(new TimerFiring(TimerKind.EVENT_TIME, 110));
    }

    @Test
    @DisplayName("fireTimer of a timer that is not pending fails")
    void fireTimerOfANonPendingTimerFails() {
        TaskletTestHarness<Long, Void, String> harness =
                TaskletTestHarness.of(new DeadlineTasklet()).applyInputs(List.of(100L));
        assertThatThrownBy(() -> harness.fireTimer(TimerKind.PROCESSING_TIME, 110))
                .isInstanceOf(AssertionError.class)
                .hasMessage("no processing_time timer pending at 110");
    }

    @Test
    @DisplayName("advanceEventTime fires due event-time timers ascending, a chained one too")
    void advanceEventTimeFiresDueEventTimeTimers() {
        TaskletTestHarness<Long, Void, String> harness = TaskletTestHarness.of(new DeadlineTasklet())
                .applyInputs(List.of(100L, 200L))
                .advanceEventTime(120);
        assertThat(harness.recordedEmits()).containsExactly("event_time@110 now=110", "event_time@115 now=115");
        assertThat(harness.pendingTimers())
                .containsExactly(
                        new TimerFiring(TimerKind.EVENT_TIME, 210),
                        new TimerFiring(TimerKind.PROCESSING_TIME, 105),
                        new TimerFiring(TimerKind.PROCESSING_TIME, 205));
    }

    @Test
    @DisplayName("cancelTimer removes the timer of the kind it names")
    void cancelTimerRemovesTheNamedKind() {
        TaskletTestHarness<Long, Void, String> harness =
                TaskletTestHarness.of(new DeadlineTasklet()).applyInputs(List.of(100L, -110L));
        assertThat(harness.pendingTimers()).containsExactly(new TimerFiring(TimerKind.PROCESSING_TIME, 105));
    }

    @Test
    @DisplayName("an event-time timer under a wall clock is refused and not pending")
    void anEventTimeTimerUnderAWallClockIsRefused() {
        TaskletTestHarness<Long, Void, String> harness =
                TaskletTestHarness.of(new WallDeadlineTasklet()).applyInputs(List.of(100L));
        assertThat(harness.recordedEmits()).containsExactly("runtime.event_time_timer_without_event_time_clock");
        assertThat(harness.pendingTimers()).isEmpty();
    }

    @Test
    @DisplayName("the harness's effect-name bound is the core's")
    void theEffectNameBoundIsTheCores() throws IOException {
        assertThat(TaskletTestHarness.MAX_EFFECT_NAME_BYTES)
                .isEqualTo(Integer.parseInt(
                        RepositoryFixture.value("test/fixtures/wire_constants.txt", "effect", "name_max_bytes")));
    }
}
