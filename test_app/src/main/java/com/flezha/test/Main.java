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
        runner.registerFactory(DoNothingStreamOperative.class)
                .registerFactory(StoreInputStreamOperative.class)
                .registerFactory(CompareFilterStreamOperative.class)
                .registerFactory(StaticEntityStreamOperative.class)
                .registerFactory(EntityStressStreamOperative.class)
                .registerFactory(BoundedMailboxDropNothingStreamOperative.class)
                .registerFactory(BoundedMailboxDropNewestStreamOperative.class)
                .registerFactory(BoundedMailboxDropOldestStreamOperative.class)
                .registerFactory(StatefulCounterStreamOperative.class)
                .registerFactory(CoalescingCounterStreamOperative.class)
                .registerFactory(PersistenceOffStreamOperative.class)
                .registerFactory(HybridChannelStreamOperative.class)
                .registerFactory(MergedInputStreamOperative.class)
                .registerFactory(TransformChainStreamOperative.class)
                .registerFactory(ResumeProbeStreamOperative.class)
                .registerFactory(SchemaEvolutionStreamOperatives.V1.class)
                .registerFactory(SchemaEvolutionStreamOperatives.V2.class)
                .registerFactory(DynamicEntityStreamOperative.class)
                .registerFactory(PartitionRelayStreamOperative.class)
                .registerFactory(SessionTimeoutStreamOperative.class)
                .registerFactory(MixedStreamOperative.class)
                .registerFactory(TierStoreStreamOperative.class)
                .registerFactory(BenchEchoStreamOperative.class)
                .registerFactory(BenchKeyedStreamOperative.class)
                .registerFactory(BenchKeyedNoPersistStreamOperative.class)
                .registerFactory(CheckpointStoreStreamOperative.class)
                .registerFactory(CheckpointBatchedStoreStreamOperative.class)
                .registerFactory(BenchCheckpointStreamOperative.class)
                .registerFactory(PoisonProbeStreamOperative.class)
                .registerFactory(WedgeProbeStreamOperative.class)
                .registerFactory(CandleStreamOperative.class)
                .registerFactory(SpawnRequesterStreamOperative.class)
                .registerFactory(EchoWorkflowOperative.class)
                .registerFactory(CommitBlockWorkflowOperative.class)
                .registerFactory(SyncDurableWorkflowOperative.class)
                .registerFactory(OptOutCommitBlockWorkflowOperative.class)
                .registerFactory(TierCommitBlockWorkflowOperative.class)
                .registerFactory(SagaBlockWorkflowOperative.class)
                .registerFactory(OutboxBlockWorkflowOperative.class)
                .registerFactory(UndoOutboxWorkflowOperative.class)
                .registerFactory(ChatLoopWorkflowOperative.class)
                .registerFactory(StatelessLoopWorkflowOperative.class)
                .registerFactory(ChatRewindWorkflowOperative.class)
                .registerFactory(RetryWorkflowOperative.class)
                .registerFactory(BranchWorkflowOperative.class)
                .registerFactory(DiamondWorkflowOperative.class)
                .registerFactory(BenchChain1WorkflowOperative.class)
                .registerFactory(BenchChain4WorkflowOperative.class)
                .registerFactory(BenchChain16WorkflowOperative.class)
                .registerFactory(BenchChainOptOut4WorkflowOperative.class)
                .registerFactory(BenchLoopWorkflowOperative.class)
                // The §5 short forms. Each generated operative is registered here,
                // as every hand-written one is: nothing collects itself.
                .registerFactory(SugarDoNothingStreamOperative.class)
                .registerFactory(SugarStoreStreamOperative.class)
                .registerFactory(SugarNamedInputStreamOperative.class)
                .registerFactory(SugarWorkflowWorkflowOperative.class)
                .registerFactory(SugarStaticEntityStreamOperative.class);
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
