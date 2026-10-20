package com.flezha.sdk.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.LogLevel;
import com.flezha.sdk.Logger;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.core.Activation;
import com.flezha.sdk.runtime.core.ActivationCodec;
import com.flezha.sdk.runtime.core.ActivationResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Java side of the poll/complete seam: a small fixed set of platform
 * <em>fetcher</em> threads looping {@code poll → decode → hand off → poll},
 * and one virtual thread per activation that runs the dispatch, encodes the
 * result and calls {@code complete} itself.
 *
 * <p>The core decides what runs next, caps how much may be underway and, through
 * its pool governor, how many fetchers poll; a host's only job is to bring
 * threads, and this one brings three kinds — fetchers ({@link #fetchLoop}), a
 * virtual thread per activation ({@link #runActivation}) and the governor's
 * supervisor ({@link #superviseLoop}). Each carries the rule that shapes it.
 */
final class PollHost implements AutoCloseable {

    private static final Logger LOG = Flezha.log();

    /**
     * The base this host hands the governor: fetchers never run user code —
     * they poll, decode and hand off to a virtual thread — so two saturate the
     * core's handout. How much work is underway is the core's number.
     */
    static final int DEFAULT_FETCHERS = 2;

    /**
     * The production {@link CoreCalls}, over one core handle. One definition,
     * because a deployed runner and a local run drive the same seam.
     */
    static CoreCalls of(long core) {
        return new CoreCalls() {
            @Override
            @Nullable
            public byte[] poll(long timeoutMs) {
                return FlzCore.poll(core, timeoutMs);
            }

            @Override
            public void complete(@Nonnull byte[] result) {
                FlzCore.complete(core, result);
            }

            @Override
            public int[] poolOpen(int defaultBase) {
                int[] policy = new int[5];
                FlzCore.poolOpen(core, defaultBase, policy);
                return policy;
            }

            @Override
            public int poolSupervise() {
                return FlzCore.poolSupervise(core);
            }

            @Override
            public void poolLeft() {
                FlzCore.poolLeft(core);
            }

            @Override
            public void abandon(long token) {
                FlzCore.abandon(core, token);
            }

            @Override
            public String takeNotices() {
                return FlzCore.takeNotices();
            }
        };
    }

    /**
     * The core calls this host makes: a seam, so the fetch/dispatch model and
     * the supervisor are testable without the native library.
     */
    interface CoreCalls {
        /**
         * @return {@code null} when the poll timed out, a zero-length array
         *     when the core is shutting down, else an encoded Activation.
         */
        @Nullable
        byte[] poll(long timeoutMs);

        void complete(@Nonnull byte[] result);

        /** The governor's policy over {@code defaultBase}, as {@link FlzCore#poolOpen} writes it. */
        int[] poolOpen(int defaultBase);

        /** The governor's next decision, as {@link FlzCore#poolSupervise} answers it. */
        int poolSupervise();

        void poolLeft();

        /**
         * Re-hand an activation's untouched items and release its cell, as
         * if it had never been polled. The third and last way to answer a
         * handout; no retry-ladder attempt is consumed.
         */
        void abandon(long token);

        /** The core's notices held since the last take, as {@link FlzCore#takeNotices()} answers. */
        default String takeNotices() {
            return "[]";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A core context whose notices this host writes: the first take starts the
     * core holding them for the supervisor, the local-mode banner included, and
     * whatever an earlier context left is written now.
     */
    static long newContext() {
        logNotices(FlzCore.takeNotices());
        return FlzCore.newContext();
    }

    /**
     * Write the core's notices, as {@link FlzCore#takeNotices()} answers them,
     * through the SDK's logger, on the thread that took them and never a
     * core thread. The local-mode banner is written at {@code error}, which
     * no level hides.
     */
    static void logNotices(@Nonnull String taken) {
        try {
            for (JsonNode notice : JSON.readTree(taken)) {
                LogLevel level =
                        switch (notice.path("level").asText()) {
                            case "info" -> LogLevel.INFO;
                            case "warn" -> LogLevel.WARN;
                            default -> LogLevel.ERROR;
                        };
                LOG.log(level, "{}", notice.path("text").asText());
            }
        } catch (JsonProcessingException e) {
            LOG.error("unreadable core notices: {}", e.toString());
        }
    }

    private final CoreCalls core;
    private final Function<Activation, ActivationResult> dispatch;

    /**
     * The fetchers currently running: what {@link #close()} joins, and empty
     * once the last one left on the core's word.
     */
    private final Set<Thread> live = ConcurrentHashMap.newKeySet();

    private final int base;
    private final int ceiling;
    private final long pollTimeoutMs;
    private final long retireMs;
    private final long drainMs;
    private final AtomicInteger nextFetcherId = new AtomicInteger();
    private final ExecutorService activations = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final Runnable onFetchersDrained;
    private final Thread supervisor = new Thread(this::superviseLoop, "flz-poll-supervisor");

    PollHost(
            @Nonnull CoreCalls core,
            @Nonnull Function<Activation, ActivationResult> dispatch,
            @Nonnull Runnable onFetchersDrained) {
        this.core = core;
        this.dispatch = dispatch;
        this.onFetchersDrained = onFetchersDrained;
        int[] policy = core.poolOpen(DEFAULT_FETCHERS);
        this.base = policy[0];
        this.ceiling = policy[1];
        this.pollTimeoutMs = policy[2];
        this.retireMs = policy[3];
        this.drainMs = policy[4];
        this.supervisor.setDaemon(true);
    }

    /** Start the base fetchers in the seats the governor reserved, then its supervisor. */
    void start() {
        for (int i = 0; i < base; i++) {
            startFetcher(false);
        }
        supervisor.start();
    }

    /** The base the governor keeps the fetchers at. */
    int fetcherCount() {
        return base;
    }

    /** The ceiling the governor may grow the fetchers to. */
    int maxFetcherCount() {
        return ceiling;
    }

    /** Fetchers currently running. */
    int liveFetcherCount() {
        return live.size();
    }

    /**
     * The governor's supervisor: one answer per wait, at most a second apart,
     * each spawn answer one fetcher, and the core's notices written on each. A
     * fixed fetcher set is not enough on its own — a virtual thread that parks
     * inside {@code complete}, pushing into a consumer nobody is draining,
     * holds its fetcher's handout, and with every fetcher held the runner
     * processes nothing and reports nothing. What one more fetcher does
     * <em>not</em> buy is a thread against user code that never returns; no
     * ceiling outruns that.
     */
    private void superviseLoop() {
        while (!closing.get()) {
            int answer;
            try {
                answer = core.poolSupervise();
                logNotices(core.takeNotices());
            } catch (Throwable t) {
                if (closing.get()) {
                    return;
                }
                LOG.error("pool supervisor failed: {}", t.toString());
                // Same rule the fetch loop applies: an Exception is one bad
                // turn, an Error is the JVM telling this thread it is
                // finished. A supervisor that stopped on a transient failure
                // would leave the runner with no answer to starvation and no
                // report of it — the wedge this thread exists to close.
                if (t instanceof Error) {
                    return;
                }
                continue;
            }
            if (answer == FlzCore.POOL_STOP) {
                return;
            }
            if ((answer == FlzCore.POOL_SPAWN_BASE || answer == FlzCore.POOL_SPAWN_GROWN) && !closing.get()) {
                startFetcher(answer == FlzCore.POOL_SPAWN_GROWN);
            }
        }
    }

    /**
     * Start one fetcher into a seat the governor reserved. A thread that never
     * ran gives its seat back and is not counted: a phantom fetcher would keep
     * the set non-empty, so the runner would never read as drained.
     */
    private void startFetcher(boolean grown) {
        Thread fetcher = new Thread(() -> fetchLoop(grown), "flz-fetcher-" + nextFetcherId.incrementAndGet());
        fetcher.setDaemon(true);
        live.add(fetcher);
        try {
            fetcher.start();
        } catch (Throwable t) {
            live.remove(fetcher);
            core.poolLeft();
            LOG.error("could not start a fetcher", t);
        }
    }

    /**
     * One fetcher: never runs user code, so it is always available to take
     * the core's next activation. Per-activation failures are logged, never
     * rethrown — a fetcher must not die on one bad activation.
     *
     * <p><b>Fetchers stay platform threads.</b> {@code FlzCore.poll} blocks in
     * native code, and a native frame pins its carrier — virtual fetchers
     * would pin one carrier each and buy nothing. The short native calls the
     * virtual threads make ({@code complete}, emits) pin only for their
     * duration, which is how JNI and virtual threads are meant to compose.
     */
    private void fetchLoop(boolean grown) {
        boolean leftOnWord = false;
        try {
            leftOnWord = fetchTurns(grown);
        } catch (Throwable t) {
            // Anything that escaped the loop's own per-turn guards. The seat
            // goes back below, and the governor replaces a fetcher the pool
            // is short of.
            LOG.error("fetcher thread ended unexpectedly; the pool replaces it", t);
        } finally {
            core.poolLeft();
        }
        live.remove(Thread.currentThread());
        if (leftOnWord && live.isEmpty()) {
            onFetchersDrained.run();
        }
    }

    /**
     * The fetch turns: {@code true} when the core's word or the host's stop
     * ended them, {@code false} on retiring. The core says when: a zero-length
     * poll result is the core shutting down, which it reports only once the
     * shutdown sequence has shipped every Cell's state through these same
     * fetchers. The flag is what a fetcher spawned late reads.
     */
    private boolean fetchTurns(boolean grown) {
        long idleMs = 0;
        while (!closing.get()) {
            byte[] bytes;
            try {
                bytes = core.poll(pollTimeoutMs);
            } catch (Throwable t) {
                if (closing.get()) {
                    break;
                }
                LOG.error("core poll failed: {}", t.toString());
                // An Exception is one bad turn — retry it. An Error is the JVM
                // telling this thread it is finished (OOM, StackOverflow, a
                // LinkageError); retrying it forever would spin a fetcher that
                // can never fetch. End the thread and let the replacement try
                // with a fresh stack.
                if (t instanceof Error) {
                    throw (Error) t;
                }
                continue;
            }
            if (bytes == null) {
                if (grown && (idleMs += pollTimeoutMs) >= retireMs) {
                    return false;
                }
                continue;
            }
            if (bytes.length == 0) {
                break;
            }
            idleMs = 0;
            Activation activation;
            try {
                activation = ActivationCodec.decodeActivation(bytes);
            } catch (Throwable t) {
                LOG.error("activation decode failed: {}", t.toString());
                continue;
            }
            try {
                activations.execute(() -> runActivation(activation));
            } catch (RejectedExecutionException e) {
                // close() shut the executor down between the poll and here.
                break;
            }
        }
        return true;
    }

    /**
     * Run one activation and answer it exactly once, on a virtual thread of
     * its own: an activation that blocks for a minute inside user code costs a
     * stack and nothing else — the fetchers keep fetching and the core keeps
     * handing out work for other Cells. Concurrency is bounded by the
     * core's jobs-underway limit, never by the fetcher count.
     *
     * <p>Per-Cell ordering needs no help here: exclusivity is the core's
     * handout guarantee (one outstanding activation per Cell), and this
     * thread completes its activation only after the dispatch returns, so the
     * next one for that Cell cannot start early.
     *
     * <p>The {@code finally} is the contract, not politeness: that same
     * exclusivity means an activation that returns without {@code complete}
     * retires its cell for the life of the process. A throwable from the
     * dispatcher, from encoding the result, or from {@code complete} itself
     * therefore abandons; logging it and returning would retire the cell
     * silently.
     */
    private void runActivation(Activation activation) {
        boolean answered = false;
        try {
            ActivationResult result = dispatch.apply(activation);
            byte[] encoded = ActivationCodec.encodeResult(result);
            core.complete(encoded);
            answered = true;
        } catch (Throwable t) {
            LOG.error("activation processing failed: {}", t.toString());
        } finally {
            if (!answered) {
                abandonQuietly(activation.token());
            }
        }
    }

    private void abandonQuietly(long token) {
        try {
            core.abandon(token);
        } catch (Throwable t) {
            // The core refuses an unknown token: `complete` had already
            // landed and only the bookkeeping after it threw. Nothing is
            // wedged, so this is a log line and not an escalation.
            LOG.warn("abandoning activation {} failed: {}", token, t.toString());
        }
    }

    /**
     * Stop fetching without the core's word. A local run ends while the
     * process-wide poll core keeps running — there is no controller session
     * to shut down, and shutting the core would stop every later run in the
     * process — so the flag is raised first and the fetchers leave on their
     * next poll timeout instead.
     */
    void stopFetching() {
        closing.set(true);
        close();
    }

    /**
     * Stop fetching and let in-flight activations finish. Draining the
     * virtual threads is not politeness: each still holds an activation the
     * core is waiting on, and the caller frees the core context next.
     */
    @Override
    public void close() {
        // The supervisor first: it is parked in a bounded wait, and joining it
        // before the fetchers means it cannot grow the pool behind the join
        // that is meant to drain it. Both leave on the core's word; the flag
        // is raised only after, so no fetcher leaves before the shutdown
        // sequence has been served.
        joinQuietly(supervisor);
        for (Thread fetcher : List.copyOf(live)) {
            joinQuietly(fetcher);
        }
        closing.set(true);
        logNotices(core.takeNotices());
        activations.shutdown();
        try {
            if (!activations.awaitTermination(drainMs, TimeUnit.MILLISECONDS)) {
                LOG.warn("in-flight activations did not finish within {}ms", drainMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(drainMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
