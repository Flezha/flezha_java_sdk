package com.flezha.test;

import com.flezha.sdk.ConsoleLogger;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaRunner;
import com.flezha.sdk.LogLevel;

/**
 * Mirror of {@code test/apps/rust_runner/src/main.rs}. Accepts the
 * namespace as its first command-line argument; {@code run()} keeps the
 * process alive until shutdown, and a watcher thread exits on stdin EOF
 * because the Elixir e2e suite manages this app as an Erlang Port and
 * terminates it by closing the port (= our stdin).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String namespace = args.length > 0 ? args[0] : "default";
        // The conformance run answers a file and exits; it never connects. The
        // drill names the path in the environment, which reaches the app
        // whatever shell the manifest's launch line goes through.
        String report = System.getenv("FLEZHA_CONFORMANCE_REPORT");
        if (report == null || report.isEmpty()) {
            report = flagValue(args, "--conformance-report");
        }
        if (report != null) {
            ConformanceRun.writeReport(report);
            return;
        }
        spawnStdinEofExit();
        // The same sink and the same default the other conformance runners
        // supply, so one FLEZHA_LOG_LEVEL governs every language in a drill.
        FlezhaRunner runner = Flezha.createRunner(namespace)
                .logger(new ConsoleLogger(LogLevel.from(System.getenv(ConsoleLogger.FLEZHA_LOG_LEVEL), LogLevel.INFO)));
        // One row of the refusal table instead of the fixture set: a refused
        // topology must not reach a connected runner.
        String refusalFixture = System.getenv("FLEZHA_REFUSAL_FIXTURE");
        if (refusalFixture != null && !refusalFixture.isEmpty()) {
            ConformanceRun.registerOnly(runner, refusalFixture);
            runner.run();
            return;
        }
        define(runner).run();
    }

    /**
     * The value of {@code --flag <value>}, wherever it sits in the argument
     * list — the namespace is positional and every mode is selected after it.
     */
    private static String flagValue(String[] args, String flag) {
        for (int at = 0; at < args.length - 1; at++) {
            if (args[at].equals(flag)) {
                return args[at + 1];
            }
        }
        return null;
    }

    /**
     * Declare every fixture class on {@code runner}, in the order the Rust test
     * app declares its twins. Shared with {@code FactoryIdsTest}, so the pinned
     * factory-id set is the set this app actually advertises.
     */
    public static FlezhaRunner define(FlezhaRunner runner) {
        runner.registerFactory(DoNothingUnboundedOperator.class)
                .registerFactory(StoreInputUnboundedOperator.class)
                .registerFactory(CompareFilterUnboundedOperator.class)
                .registerFactory(StaticCellUnboundedOperator.class)
                .registerFactory(CellStressUnboundedOperator.class)
                .registerFactory(BoundedMailboxDropNothingUnboundedOperator.class)
                .registerFactory(BoundedMailboxDropNewestUnboundedOperator.class)
                .registerFactory(BoundedMailboxDropOldestUnboundedOperator.class)
                .registerFactory(StatefulCounterUnboundedOperator.class)
                .registerFactory(CoalescingCounterUnboundedOperator.class)
                .registerFactory(PersistenceOffUnboundedOperator.class)
                .registerFactory(SleepingCounterUnboundedOperator.class)
                .registerFactory(HybridChannelUnboundedOperator.class)
                .registerFactory(MergedInputUnboundedOperator.class)
                .registerFactory(TransformChainUnboundedOperator.class)
                .registerFactory(ResumeProbeUnboundedOperator.class)
                .registerFactory(SchemaEvolutionUnboundedOperators.V1.class)
                .registerFactory(SchemaEvolutionUnboundedOperators.V2.class)
                .registerFactory(DynamicCellUnboundedOperator.class)
                .registerFactory(PartitionRelayUnboundedOperator.class)
                .registerFactory(SessionTimeoutUnboundedOperator.class)
                .registerFactory(MixedUnboundedOperator.class)
                .registerFactory(TierStoreUnboundedOperator.class)
                .registerFactory(TierChainUnboundedOperator.class)
                .registerFactory(BenchEchoUnboundedOperator.class)
                .registerFactory(BenchKeyedUnboundedOperator.class)
                .registerFactory(BenchKeyedNoPersistUnboundedOperator.class)
                .registerFactory(CheckpointStoreUnboundedOperator.class)
                .registerFactory(CheckpointBatchedStoreUnboundedOperator.class)
                .registerFactory(BenchCheckpointUnboundedOperator.class)
                .registerFactory(PoisonProbeUnboundedOperator.class)
                .registerFactory(WedgeProbeUnboundedOperator.class)
                .registerFactory(CandleUnboundedOperator.class)
                .registerFactory(SpawnRequesterUnboundedOperator.class)
                .registerFactory(SpawnOnceRequesterUnboundedOperator.class)
                .registerFactory(SpawnHoldRequesterUnboundedOperator.class)
                .registerFactory(EchoBoundedOperator.class)
                .registerFactory(TypedOnceBoundedOperator.class)
                .registerFactory(CommitBlockBoundedOperator.class)
                .registerFactory(SyncDurableBoundedOperator.class)
                .registerFactory(OptOutCommitBlockBoundedOperator.class)
                .registerFactory(TierCommitBlockBoundedOperator.class)
                .registerFactory(SagaBlockBoundedOperator.class)
                .registerFactory(KeyedBoundedOperators.KeyedBlock.class)
                .registerFactory(KeyedBoundedOperators.KeyedSaga.class)
                .registerFactory(OutboxBlockBoundedOperator.class)
                .registerFactory(UndoOutboxBoundedOperator.class)
                .registerFactory(FailedStageBoundedOperator.class)
                .registerFactory(ChatLoopBoundedOperator.class)
                .registerFactory(NoStateLoopBoundedOperator.class)
                .registerFactory(KeyedLoopBoundedOperator.class)
                .registerFactory(ChatRewindBoundedOperator.class)
                .registerFactory(FailingTurnLoopBoundedOperator.class)
                .registerFactory(FailingTurnAllOrNothingBoundedOperator.class)
                .registerFactory(ReplyBlockLoopBoundedOperator.class)
                .registerFactory(RetryBoundedOperator.class)
                .registerFactory(BranchBoundedOperator.class)
                .registerFactory(DiamondBoundedOperator.class)
                .registerFactory(BenchChain1BoundedOperator.class)
                .registerFactory(BenchChain4BoundedOperator.class)
                .registerFactory(BenchChain16BoundedOperator.class)
                .registerFactory(BenchChainOptOut4BoundedOperator.class)
                .registerFactory(BenchLoopBoundedOperator.class)
                .registerFactory(BoundedTimerOperators.TimerLoop.class)
                .registerFactory(BoundedTimerOperators.EventTimer.class)
                .registerFactory(StaticCellBoundedOperator.class)
                // The §5 short forms, registered by the classes the author wrote, as
                // every hand-written operator is: nothing collects itself.
                .registerFactory(SugarDoNothing.class)
                .registerFactory(SugarStore.class)
                .registerFactory(SugarNamedInput.class)
                .registerFactory(SugarBounded.class)
                .registerFactory(SugarBoundedPerInput.class)
                .registerFactory(SugarStateOnly.class)
                .registerFactory(SugarStaticCellUnboundedOperator.class);
        return runner;
    }

    private static void spawnStdinEofExit() {
        Thread watcher = new Thread(() -> {
            try {
                byte[] buf = new byte[64];
                while (System.in.read(buf) != -1) {
                    // Discard; only EOF matters.
                }
            } catch (Exception ignored) {
                // A broken stdin counts as EOF.
            }
            System.exit(0);
        }, "stdin-eof-exit");
        watcher.setDaemon(true);
        watcher.start();
    }
}
