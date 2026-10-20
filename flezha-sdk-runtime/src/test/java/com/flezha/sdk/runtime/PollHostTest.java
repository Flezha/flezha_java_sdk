package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.CapturedStderr;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.core.Activation;
import com.flezha.sdk.runtime.core.ActivationCodecInverses;
import com.flezha.sdk.runtime.core.ActivationKind;
import com.flezha.sdk.runtime.core.ActivationOutcome;
import com.flezha.sdk.runtime.core.ActivationResult;
import com.flezha.sdk.runtime.core.CellRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The concurrency contract of the Java poll host: fetchers never run user
 * code, so an activation blocked in user code cannot consume one, a virtual
 * thread completes its activation only after the dispatch returns, and the
 * fetchers are the ones the core's pool governor asks for. The governor's
 * policy itself is tested in the core; here its decisions are scripted.
 */
class PollHostTest {

    /** A stand-in core: hands out queued activations and records answers. */
    private static final class FakeCore implements PollHost.CoreCalls {
        private final LinkedBlockingQueue<byte[]> handouts = new LinkedBlockingQueue<>();
        private final AtomicInteger completed = new AtomicInteger();
        private final List<Long> abandoned = new CopyOnWriteArrayList<>();
        /** Set to make every {@code complete} throw, as a refusing core would. */
        private volatile boolean completeThrows = false;
        /** How many {@code poll} calls still throw an Error (killing a fetcher). */
        private final AtomicInteger pollErrors = new AtomicInteger();
        /** The policy {@code poolOpen} answers: base, ceiling, poll timeout, retire time, drain bound. */
        private final int[] policy = {2, 20, 250, 30_000, 5_000};
        /** The governor's decisions {@code poolSupervise} answers in turn, idle once none is left. */
        private final LinkedBlockingQueue<Integer> decisions = new LinkedBlockingQueue<>();
        /** Every seat given back. */
        private final AtomicInteger seatsLeft = new AtomicInteger();
        /** Every {@code poolSupervise} call. */
        private final AtomicInteger supervisorCalls = new AtomicInteger();
        /** A {@code poolSupervise} call answered after a seat was given back. */
        private volatile boolean supervisedAfterALeave = false;
        /** How many {@code poolSupervise} calls still throw an Exception. */
        private final AtomicInteger supervisorErrors = new AtomicInteger();
        /** What the next {@code takeNotices} answers. */
        private final AtomicReference<String> notices = new AtomicReference<>("[]");
        /** The thread the supervisor loop runs on, as this core sees it. */
        private volatile Thread supervisor;
        /** What {@code FlzCore.shutdown} leaves behind: every poll answers shutting down, the supervisor stop. */
        private volatile boolean shuttingDown = false;

        FakeCore base(int base) {
            policy[0] = base;
            return this;
        }

        void handOut(Activation activation) {
            handouts.add(ActivationCodecInverses.encodeActivation(activation));
        }

        void shutDown() {
            shuttingDown = true;
            handouts.add(new byte[0]);
        }

        @Override
        @Nullable
        public byte[] poll(long timeoutMs) {
            // An Error, not an Exception: an Exception is one bad turn the
            // loop retries, an Error ends the thread — which is the death a
            // replacement has to cover.
            if (pollErrors.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new StackOverflowError("fetcher died");
            }
            if (shuttingDown) {
                return new byte[0];
            }
            try {
                return handouts.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void complete(byte[] result) {
            // Decoding an ActivationResult is the core's side of the ABI; the
            // host contract under test is only that `complete` is called.
            if (completeThrows) {
                throw new IllegalStateException("core refused the completion");
            }
            completed.incrementAndGet();
        }

        @Override
        public int[] poolOpen(int defaultBase) {
            return policy.clone();
        }

        @Override
        public int poolSupervise() {
            supervisor = Thread.currentThread();
            supervisorCalls.incrementAndGet();
            if (supervisorErrors.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("pool supervisor failed");
            }
            Integer answer = null;
            try {
                answer = decisions.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            supervisedAfterALeave |= seatsLeft.get() > 0;
            if (answer != null) {
                return answer;
            }
            return shuttingDown ? FlzCore.POOL_STOP : 0;
        }

        @Override
        public void poolLeft() {
            seatsLeft.incrementAndGet();
        }

        @Override
        public String takeNotices() {
            return notices.getAndSet("[]");
        }

        @Override
        public void abandon(long token) {
            abandoned.add(token);
        }
    }

    private static Activation activation(long token, String cellKey) {
        return new Activation(
                token,
                new CellRef("op-1", 0, Optional.of(cellKey)),
                new ActivationKind.Work(List.of()),
                0,
                "factory",
                Optional.empty());
    }

    private static ActivationResult ok(Activation activation) {
        return new ActivationResult(activation.token(), new ActivationOutcome.Work(List.of()));
    }

    private static PollHost start(FakeCore core, Function<Activation, ActivationResult> dispatch) {
        return start(core, dispatch, () -> {});
    }

    private static PollHost start(
            FakeCore core, Function<Activation, ActivationResult> dispatch, Runnable onFetchersDrained) {
        PollHost host = new PollHost(core, dispatch, onFetchersDrained);
        host.start();
        return host;
    }

    /** What {@code FlezhaImpl.close()} does: the core is told first, and the host follows its word. */
    private static void close(FakeCore core, PollHost host) {
        core.shutDown();
        host.close();
    }

    @Test
    @DisplayName("Blocking user code does not consume a fetcher")
    @SuppressWarnings("try")
    void blockedActivationsDoNotConsumeFetchers() throws Exception {
        FakeCore core = new FakeCore();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch unblockedDispatched = new CountDownLatch(4);
        Function<Activation, ActivationResult> dispatch = activation -> {
            if (activation.target().cellKey().orElseThrow().startsWith("blocked")) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                unblockedDispatched.countDown();
            }
            return ok(activation);
        };

        // As many blocked activations as there are fetchers: with dispatch
        // running on the fetcher itself, nothing below would ever be picked
        // up.
        try (PollHost ignored = start(core, dispatch)) {
            core.handOut(activation(1, "blocked-a"));
            core.handOut(activation(2, "blocked-b"));
            for (long token = 3; token <= 6; token++) {
                core.handOut(activation(token, "healthy-" + token));
            }

            assertThat(unblockedDispatched.await(10, TimeUnit.SECONDS))
                    .as("work for unblocked cells was served while two activations blocked")
                    .isTrue();
            assertThat(gate.getCount())
                    .as("the blocked activations really were still blocked")
                    .isEqualTo(1);
            gate.countDown();
        }
    }

    /**
     * Per-Cell exclusivity is the core's guarantee — it hands out the next
     * activation for a Cell only once the previous one completes. The host
     * keeps that true by completing only after the dispatch returns; a host
     * that completed early would let two activations for one Cell overlap.
     */
    @Test
    @DisplayName("per-cell order holds across successive activations")
    @SuppressWarnings("try")
    void perCellOrderHoldsAcrossSuccessiveActivations() throws Exception {
        FakeCore core = new FakeCore();
        List<String> trace = new CopyOnWriteArrayList<>();
        AtomicInteger handedOut = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Function<Activation, ActivationResult> dispatch = activation -> {
            trace.add("enter-" + activation.token());
            if (activation.token() == 1) {
                insideFirst.countDown();
                await(releaseFirst);
            }
            trace.add("exit-" + activation.token());
            // The core's exclusivity, reproduced: the next activation for this
            // Cell becomes available only now.
            int next = handedOut.incrementAndGet() + 1;
            if (next <= 3) {
                core.handOut(activation(next, "same-cell"));
            } else {
                done.countDown();
            }
            return ok(activation);
        };

        try (PollHost ignored = start(core, dispatch)) {
            core.handOut(activation(1, "same-cell"));
            assertThat(insideFirst.await(10, TimeUnit.SECONDS))
                    .as("the first activation is held inside the dispatch")
                    .isTrue();
            assertThat(new ArrayList<>(trace))
                    .as("nothing follows an activation that has not returned")
                    .containsExactly("enter-1");
            releaseFirst.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(new ArrayList<>(trace))
                .containsExactly("enter-1", "exit-1", "enter-2", "exit-2", "enter-3", "exit-3");
    }

    /**
     * Per-cell exclusivity IS the handout, so an activation that returns
     * without an answer retires its Cell for the life of the process. Every
     * way the answer can be missed — the dispatch throwing, the result failing
     * to encode, `complete` itself throwing — must abandon instead.
     */
    @Test
    @DisplayName("a throwing dispatch abandons rather than dropping the activation")
    @SuppressWarnings("try")
    void aThrowingDispatchAbandons() throws Exception {
        FakeCore core = new FakeCore().base(1);
        CountDownLatch dispatched = new CountDownLatch(1);
        Function<Activation, ActivationResult> dispatch = activation -> {
            dispatched.countDown();
            throw new IllegalStateException("dispatcher blew up");
        };

        CapturedStderr stderr = CapturedStderr.start();
        try (stderr;
                PollHost ignored = start(core, dispatch)) {
            core.handOut(activation(7, "unlucky"));
            assertThat(dispatched.await(10, TimeUnit.SECONDS)).isTrue();
            awaitTrue(() -> core.abandoned.contains(7L), "the activation was abandoned");
        }
        assertThat(core.completed.get()).isZero();
        assertThat(stderr.lines())
                .containsExactly("[flezha] activation processing failed: "
                        + "java.lang.IllegalStateException: dispatcher blew up");
    }

    @Test
    @DisplayName("a throwing complete abandons rather than dropping the activation")
    @SuppressWarnings("try")
    void aThrowingCompleteAbandons() throws Exception {
        FakeCore core = new FakeCore().base(1);
        core.completeThrows = true;

        CapturedStderr stderr = CapturedStderr.start();
        try (stderr;
                PollHost ignored = start(core, PollHostTest::ok)) {
            core.handOut(activation(8, "unlucky"));
            awaitTrue(() -> core.abandoned.contains(8L), "the activation was abandoned");
        }
        assertThat(stderr.lines())
                .containsExactly("[flezha] activation processing failed: "
                        + "java.lang.IllegalStateException: core refused the completion");
    }

    /**
     * A runner whose last fetcher died stays connected, heartbeating and
     * processing nothing — the wedge this host must not be able to reach on
     * its own. The dead fetcher gives its seat back; the governor's base
     * answer is what replaces it.
     */
    @Test
    @DisplayName("a fetcher killed by a throwable gives its seat back and a base seat replaces it")
    void aDeadFetcherIsReplaced() throws Exception {
        FakeCore core = new FakeCore();
        core.pollErrors.set(1);

        CapturedStderr stderr = CapturedStderr.start();
        try (stderr;
                PollHost host = start(core, PollHostTest::ok)) {
            awaitTrue(() -> core.seatsLeft.get() == 1 && host.liveFetcherCount() == 1, "a fetcher died");
            core.decisions.add(FlzCore.POOL_SPAWN_BASE);
            awaitTrue(() -> host.liveFetcherCount() == host.fetcherCount(), "the base count came back");
            core.handOut(activation(9, "after-the-death"));
            awaitTrue(() -> core.completed.get() == 1, "a replacement fetcher served new work");
        }
        List<String> lines = stderr.lines();
        assertThat(lines)
                .startsWith(
                        "[flezha] core poll failed: java.lang.StackOverflowError: fetcher died",
                        "[flezha] fetcher thread ended unexpectedly; the pool replaces it",
                        "java.lang.StackOverflowError: fetcher died");
        assertThat(lines.subList(3, lines.size()))
                .as("the rest is the dead fetcher's stack trace")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("\tat "));
    }

    /**
     * The other half of the core's bring-your-own-thread contract: without a
     * grown fetcher a runner whose fetchers are all held processes nothing,
     * recovers from nothing, and reports nothing — measured as 0 of 20
     * payloads delivered, forever.
     */
    @Test
    @DisplayName("a grown seat is a fetcher that serves and retires once idle")
    void aGrownSeatServesAndRetires() throws Exception {
        FakeCore core = new FakeCore().base(1);
        core.policy[3] = 500;
        try (PollHost host = start(core, PollHostTest::ok)) {
            core.decisions.add(FlzCore.POOL_SPAWN_GROWN);
            core.decisions.add(FlzCore.POOL_SPAWN_GROWN);
            awaitTrue(() -> host.liveFetcherCount() == 3, "each grown seat is a fetcher");
            core.handOut(activation(11, "after-the-growth"));
            awaitTrue(() -> core.completed.get() == 1, "a grown fetcher served work");
            // Two timed-out polls of the core's 250 ms are the 500 ms retire time.
            awaitTrue(() -> host.liveFetcherCount() == 1, "the grown fetchers retired and the base stayed");
            assertThat(core.seatsLeft.get())
                    .as("every retired fetcher gives its seat back")
                    .isEqualTo(2);
        }
    }

    /**
     * Nothing in this host may stop on its own. A supervisor that ended on a
     * transient failure would leave the runner with no answer to starvation
     * and no report of it — the exact wedge the thread exists to close.
     */
    @Test
    @DisplayName("the supervisor survives a failing answer")
    void supervisorSurvivesAFailingAnswer() throws Exception {
        FakeCore core = new FakeCore().base(1);
        core.supervisorErrors.set(2);
        CapturedStderr stderr = CapturedStderr.start();
        try (stderr;
                PollHost host = start(core, PollHostTest::ok)) {
            awaitTrue(() -> core.supervisorErrors.get() == 0, "both failing answers were asked for");
            core.decisions.add(FlzCore.POOL_SPAWN_GROWN);
            awaitTrue(() -> host.liveFetcherCount() == 2, "the supervisor still starts fetchers");
        }
        String failed = "[flezha] pool supervisor failed: java.lang.IllegalStateException: pool supervisor failed";
        assertThat(stderr.lines()).containsExactly(failed, failed);
    }

    /** An idle runner still writes what the core said: the supervisor takes the notices on every answer. */
    @Test
    @DisplayName("an idle runner writes the core's notices on each supervisor answer")
    void anIdleRunnerWritesTheCoresNotices() throws Exception {
        FakeCore core = new FakeCore().base(1);
        CapturedStderr stderr = CapturedStderr.start();
        PollHost host = start(core, PollHostTest::ok);
        try (stderr) {
            core.notices.set("[{\"level\":\"warn\",\"target\":\"t\",\"text\":\"Connection attempt 2 failed\"}]");
            awaitTrue(() -> stderr.lines().contains("[flezha] Connection attempt 2 failed"), "the notice was written");
        } finally {
            close(core, host);
        }
    }

    /** A dispatch that never returns holds the stop no longer than the governor's drain bound. */
    @Test
    @DisplayName("close() waits for in-flight activations no longer than the drain bound")
    void closeIsBoundedByTheDrain() throws Exception {
        FakeCore core = new FakeCore().base(1);
        core.policy[4] = 300;
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch dispatching = new CountDownLatch(1);
        Function<Activation, ActivationResult> dispatch = activation -> {
            dispatching.countDown();
            await(never, 30);
            return ok(activation);
        };
        CapturedStderr stderr = CapturedStderr.start();
        try (stderr) {
            PollHost host = start(core, dispatch);
            core.handOut(activation(1, "wedged"));
            assertThat(dispatching.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            close(core, host);
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(300 + 3_000));
        } finally {
            never.countDown();
        }
        assertThat(stderr.lines()).contains("[flezha] in-flight activations did not finish within 300ms");
    }

    @Test
    @DisplayName("close() joins the supervisor")
    void closeJoinsTheSupervisor() throws Exception {
        FakeCore core = new FakeCore().base(1);
        PollHost host = start(core, PollHostTest::ok);
        awaitTrue(() -> core.supervisorCalls.get() > 0, "the supervisor asked at least once");
        close(core, host);
        Thread supervisor = core.supervisor;
        supervisor.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(supervisor.isAlive())
                .as("the supervisor was still parking after close() returned")
                .isFalse();
    }

    /** The governor reads a fetcher that leaves under a live supervisor as a pool to replace. */
    @Test
    @DisplayName("a local run's stop ends the supervisor before any fetcher leaves")
    void aLocalStopEndsTheSupervisorFirst() throws Exception {
        FakeCore core = new FakeCore();
        // Polls shorter than the supervisor's wait, as the core's are.
        core.policy[2] = 2;
        PollHost host = start(core, PollHostTest::ok);
        awaitTrue(() -> core.supervisorCalls.get() > 0, "the supervisor asked at least once");
        host.stopFetching();
        assertThat(core.seatsLeft.get())
                .as("both fetchers gave their seats back")
                .isEqualTo(2);
        assertThat(core.supervisedAfterALeave)
                .as("the supervisor asked the governor after a fetcher left")
                .isFalse();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier done, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting for %s", what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    /**
     * The activation is released by the fetcher drain itself, so close() is
     * provably still running when the dispatch returns — the overlap is forced
     * rather than sampled from a window.
     */
    @Test
    @DisplayName("close() lets in-flight activations finish and complete")
    void closeCompletesInFlightActivations() throws Exception {
        FakeCore core = new FakeCore();
        CountDownLatch dispatching = new CountDownLatch(1);
        CountDownLatch fetchersDrained = new CountDownLatch(1);
        Function<Activation, ActivationResult> dispatch = activation -> {
            dispatching.countDown();
            await(fetchersDrained);
            return ok(activation);
        };

        PollHost host = start(core, dispatch, fetchersDrained::countDown);
        core.handOut(activation(1, "in-flight"));
        assertThat(dispatching.await(10, TimeUnit.SECONDS)).isTrue();

        close(core, host);
        assertThat(core.completed.get())
                .as("close() returned before the in-flight activation completed")
                .isEqualTo(1);
    }

    /** Wait on a latch the test is about to release; an interrupt is a failure. */
    private static void await(CountDownLatch latch) {
        await(latch, 10);
    }

    private static void await(CountDownLatch latch, long seconds) {
        try {
            assertThat(latch.await(seconds, TimeUnit.SECONDS))
                    .as("the test released the dispatch")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
