package com.flezha.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.ConsoleLogger;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.FlezhaRunner;
import com.flezha.sdk.LogLevel;
import com.flezha.sdk.SendOpts;
import com.flezha.sdk.native_.FlzCore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mirror of {@code test/apps/rust_runner/src/main.rs}. Accepts the
 * namespace as its first command-line argument; {@code run()} keeps the
 * process alive until shutdown, and the core's standard-input watch ends it
 * on EOF because the Elixir e2e suite manages this app as an Erlang Port and
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
        FlzCore.watchStdinEof();
        // The same output and the same default the other conformance runners
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
        // The drill starts once the runner has: a send before start() returns
        // is refused `runtime.runner_not_started`.
        define(runner).start();
        String localSends = System.getenv("FLEZHA_LOCAL_SEND_SCRIPT");
        if (localSends != null && !localSends.isEmpty()) {
            startLocalSends(runner, localSends);
        }
        runner.awaitTermination();
    }

    /**
     * The local-send drill: a JSON array of {@code {relay, channel,
     * message, when}} sends this app's own code makes with {@code local},
     * each once the file {@code when} names exists, printing
     * {@code local_send <relay> ok} or {@code local_send <relay> err <code>}.
     * An entry with {@code call} calls its {@code value} instead, printing
     * {@code call <relay> ok <answer>} or {@code call <relay> err <code>}.
     */
    private static void startLocalSends(FlezhaRunner runner, String script) throws Exception {
        JsonNode sends = new ObjectMapper().readTree(script);
        Thread drill = new Thread(() -> {
            for (JsonNode send : sends) {
                String relay = send.path("relay").asText();
                try {
                    while (!Files.exists(Path.of(send.path("when").asText()))) {
                        Thread.sleep(50);
                    }
                    System.out.println(drillStep(runner, send));
                } catch (FlezhaException e) {
                    String verb = send.path("call").asBoolean() ? "call " : "local_send ";
                    System.out.println(verb + relay + " err " + e.code());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "local-send-drill");
        drill.setDaemon(true);
        drill.start();
    }

    /**
     * One drill entry: a local send of its {@code message}, or with {@code call} a call of its
     * {@code value} through the controller, answering {@code call <relay> ok <answer JSON>}.
     */
    private static String drillStep(FlezhaRunner runner, JsonNode step) {
        String relay = step.path("relay").asText();
        String channel = step.path("channel").asText();
        if (!step.path("call").asBoolean()) {
            runner.send(relay, step.path("message").asText(), channel, SendOpts.of().local());
            return "local_send " + relay + " ok";
        }
        JsonNode value = step.path("value");
        Object payload = value.isTextual() ? value.asText() : value;
        return "call " + relay + " ok " + runner.call(relay, channel, payload, JsonNode.class);
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
        runner.registerFactory(DoNothingRelay.class)
                .registerFactory(CompleteFlowRelay.class)
                .registerFactory(StoreInputRelay.class)
                .registerFactory(CompareFilterRelay.class)
                .registerFactory(StaticCellRelay.class)
                .registerFactory(CellStressRelay.class)
                .registerFactory(BoundedMailboxDropNothingRelay.class)
                .registerFactory(BoundedMailboxDropNewestRelay.class)
                .registerFactory(BoundedMailboxDropOldestRelay.class)
                .registerFactory(StatefulCounterRelay.class)
                .registerFactory(CoalescingCounterRelay.class)
                .registerFactory(PersistenceOffRelay.class)
                .registerFactory(SleepingCounterRelay.class)
                .registerFactory(HybridChannelRelay.class)
                .registerFactory(MergedInputRelay.class)
                .registerFactory(TransformChainRelay.class)
                .registerFactory(ResumeProbeRelay.class)
                .registerFactory(SchemaEvolutionFlowRelays.V1.class)
                .registerFactory(SchemaEvolutionFlowRelays.V2.class)
                .registerFactory(DynamicCellRelay.class)
                .registerFactory(PartitionPassRelay.class)
                .registerFactory(SessionTimeoutRelay.class)
                .registerFactory(TimerCapRelay.class)
                .registerFactory(TimerChurnRelay.class)
                .registerFactory(MixedRelay.class)
                .registerFactory(TierStoreRelay.class)
                .registerFactory(RemoteTierStoreRelay.class)
                .registerFactory(TierChainRelay.class)
                .registerFactory(BenchEchoRelay.class)
                .registerFactory(BenchKeyedRelay.class)
                .registerFactory(BenchKeyedNoPersistRelay.class)
                .registerFactory(CheckpointStoreRelay.class)
                .registerFactory(CheckpointBatchedStoreRelay.class)
                .registerFactory(BenchCheckpointRelay.class)
                .registerFactory(PoisonProbeRelay.class)
                .registerFactory(WedgeProbeRelay.class)
                .registerFactory(CandleRelay.class)
                .registerFactory(SpawnRequesterRelay.class)
                .registerFactory(SpawnOnceRequesterRelay.class)
                .registerFactory(SpawnHoldRequesterRelay.class)
                .registerFactory(EchoRelay.class)
                .registerFactory(TypedOnceRelay.class)
                .registerFactory(CommitBlockRelay.class)
                .registerFactory(EffectBlockRelay.class)
                .registerFactory(EffectFlowRelay.class)
                .registerFactory(SyncDurableRelay.class)
                .registerFactory(OptOutCommitBlockRelay.class)
                .registerFactory(TierCommitBlockRelay.class)
                .registerFactory(SagaBlockRelay.class)
                .registerFactory(KeyedStepsRelays.KeyedBlock.class)
                .registerFactory(KeyedStepsRelays.KeyedSaga.class)
                .registerFactory(OutboxBlockRelay.class)
                .registerFactory(UndoOutboxRelay.class)
                .registerFactory(FailedStageRelay.class)
                .registerFactory(ChatLoopRelay.class)
                .registerFactory(NoStateLoopRelay.class)
                .registerFactory(ChainedStepsRelay.class)
                .registerFactory(KeyedLoopRelay.class)
                .registerFactory(ChatRewindRelay.class)
                .registerFactory(FailingTurnLoopRelay.class)
                .registerFactory(FailingTurnUndoAllRelay.class)
                .registerFactory(ReplyBlockLoopRelay.class)
                .registerFactory(RetryRelay.class)
                .registerFactory(BranchRelay.class)
                .registerFactory(DiamondRelay.class)
                .registerFactory(BenchChain1Relay.class)
                .registerFactory(BenchChain4Relay.class)
                .registerFactory(BenchChain16Relay.class)
                .registerFactory(BenchChainOptOut4Relay.class)
                .registerFactory(BenchLoopRelay.class)
                .registerFactory(StepsTimerRelays.TimerLoop.class)
                .registerFactory(StepsTimerRelays.EventTimer.class)
                .registerFactory(StaticCellSteps.class)
                .registerFactory(CallRelays.CallEcho.class)
                .registerFactory(CallRelays.CallDownstream.class)
                .registerFactory(CallRelays.CallSplit.class)
                .registerFactory(CallRelays.CallTurn.class)
                .registerFactory(RelayCallRelays.CallPricing.class)
                .registerFactory(RelayCallRelays.CallOrders.class)
                .registerFactory(RelayCallRelays.CallAgent.class)
                .registerFactory(RelayCallRelays.CallGateway.class)
                .registerFactory(RelayCallRelays.CallToolSession.class)
                .registerFactory(RelayCallRelays.CallOrdersSteps.class)
                .registerFactory(PartRelays.PartEcho.class)
                .registerFactory(PartRelays.PartEchoSteps.class)
                .registerFactory(OrdersRelay.class)
                // The §5 short forms, registered by the classes the author wrote, as
                // every hand-written relay is: nothing collects itself.
                .registerFactory(SugarDoNothing.class)
                .registerFactory(SugarStore.class)
                .registerFactory(SugarNamedInput.class)
                .registerFactory(SugarSteps.class)
                .registerFactory(SugarStepsPerInput.class)
                .registerFactory(SugarStateOnly.class)
                .registerFactory(SugarStaticCellRelay.class);
        return runner;
    }

}
