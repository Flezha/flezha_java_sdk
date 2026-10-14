package com.flezha.sdk.runtime.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.state.TimerKind;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A session works over the Cell's pending set as the core keeps it, read only when a timer call needs it. */
class TimerSessionTest {

    private static final TimerPolicy POLICY = TimerPolicy.of(2, "timer_session_test", ClockKind.WALL);

    private static Supplier<PendingTimers> pending(long... processingTimes) {
        long[] pairs = new long[processingTimes.length * 2];
        for (int i = 0; i < processingTimes.length; i++) {
            pairs[2 * i] = 1;
            pairs[2 * i + 1] = processingTimes[i];
        }
        return () -> PendingTimers.ofPairs(pairs);
    }

    @Test
    @DisplayName("a cancel of a timer an earlier invocation registered is recorded")
    void aCancelOfAnEarlierTimerIsRecorded() {
        TimerSession session = POLICY.session(pending(5_000), 1_000);

        assertThat(session.cancel(TimerKind.PROCESSING_TIME, 5_000)).isTrue();
        assertThat(session.commit(new PendingTimers()))
                .containsExactly(TimerOp.cancel(TimerKind.PROCESSING_TIME, 5_000));
    }

    @Test
    @DisplayName("the cap counts the timers already pending")
    void theCapCountsWhatIsAlreadyPending() {
        TimerSession session = POLICY.session(pending(5_000, 6_000), 1_000);

        assertThatThrownBy(() -> session.registerAfter(Duration.ofSeconds(9)))
                .hasMessageContaining("timer_cap_exceeded");
    }

    @Test
    @DisplayName("an invocation that makes no timer call never reads the pending set")
    void noTimerCallReadsNothing() {
        TimerSession session = POLICY.session(
                () -> {
                    throw new AssertionError("the pending set was read");
                },
                1_000);

        assertThat(session.commit(new PendingTimers())).isEmpty();
    }

    @Test
    @DisplayName("a firing's removal leads the ops and applies on top of the pending set")
    void aFiringRemovesItsTimerFromThePendingSet() {
        TimerSession session = POLICY.firingSession(pending(5_000), 5_000, TimerKind.PROCESSING_TIME);

        assertThat(session.cancel(TimerKind.PROCESSING_TIME, 5_000)).isFalse();
        assertThat(session.commit(new PendingTimers()))
                .containsExactly(TimerOp.cancel(TimerKind.PROCESSING_TIME, 5_000));
    }
}
