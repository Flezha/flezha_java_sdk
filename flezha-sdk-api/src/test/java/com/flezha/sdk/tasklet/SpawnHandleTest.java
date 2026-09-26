package com.flezha.sdk.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tasklet-requested spawn, api half ({@code tasklet-requested-spawn}): what the context sends
 * to the core, and how a handle reads the core's answer. The core's own suite owns the replay
 * skip, the deadline and the release; here a double answers as the core does.
 */
class SpawnHandleTest {

    private static final byte[] PAYLOAD = "p".getBytes(StandardCharsets.UTF_8);

    private final FakeCore core = new FakeCore();

    @AfterEach
    void dropRequester() {
        SpawnRequests.uninstall();
    }

    @Test
    @DisplayName("a spawn without an id asks for a generated one and answers it")
    void aSpawnWithoutAnIdAnswersTheGeneratedId() throws Exception {
        core.answer = "{\"id\":\"worker-k7m2qzxw4pd3fn2a\",\"outcome\":\"spawned\"}";
        SpawnRequests.install(core);

        SpawnResult result =
                new StubCtx(false, 42).spawnBounded("worker", PAYLOAD).await();

        assertThat(result).isEqualTo(new SpawnResult("worker-k7m2qzxw4pd3fn2a", SpawnOutcome.SPAWNED));
        assertThat(core.requests).containsExactly("42/worker//p/deliver/false");
    }

    @Test
    @DisplayName("a spawn with an id carries it and delivers by default; the error policy is carried too")
    void aSpawnWithAnIdCarriesItAndItsPolicy() throws Exception {
        core.answer = "{\"id\":\"w1\",\"outcome\":\"delivered\"}";
        SpawnRequests.install(core);
        TaskletCtx ctx = new StubCtx(false, 42);

        assertThat(ctx.spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1")).await())
                .isEqualTo(new SpawnResult("w1", SpawnOutcome.DELIVERED));
        ctx.spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1").ifRunning(IfRunning.ERROR));

        assertThat(core.requests).containsExactly("42/worker/w1/p/deliver/false", "42/worker/w1/p/error/false");
    }

    record Ticket(String symbol, int quantity) {}

    @Test
    @DisplayName("a payload is encoded as a sink with no encoder encodes it: a string as UTF-8, a record as JSON")
    void aPayloadIsEncodedAsASinkWithNoEncoder() {
        SpawnRequests.install(core);
        TaskletCtx ctx = new StubCtx(false, 42);

        ctx.spawnBounded("worker", "p");
        ctx.spawnBounded("worker", new Ticket("AAPL", 10), SpawnOptions.id("w1"));

        assertThat(core.requests)
                .containsExactly(
                        "42/worker//p/deliver/false",
                        "42/worker/w1/{\"symbol\":\"AAPL\",\"quantity\":10}/deliver/false");
    }

    @Test
    @DisplayName("complete encodes its result by the same rule: a string as UTF-8, a record as JSON")
    void completeEncodesItsResultByTheSameRule() {
        StubCtx ctx = new StubCtx(false, 42);

        ctx.complete("filled");
        ctx.complete(new Ticket("AAPL", 10));

        assertThat(ctx.completed()).containsExactly("filled", "{\"symbol\":\"AAPL\",\"quantity\":10}");
    }

    @Test
    @DisplayName("the error policy comes back as already running, in the core's own words")
    void theErrorPolicyComesBackAsAlreadyRunning() {
        String message = "an instance of `w1` is already running and if_running was Error";
        core.answer = failure("w1", "already_running", message);
        SpawnRequests.install(core);

        SpawnHandle handle = new StubCtx(false, 42)
                .spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1").ifRunning(IfRunning.ERROR));

        assertThatThrownBy(handle::await)
                .isInstanceOf(SpawnException.AlreadyRunning.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("a refusal does not fault the call; a read reports it with the core's sentence unprefixed")
    void aRefusalIsReportedOnlyByARead() {
        String message = "the controller refused the spawn: quota";
        core.answer = failure("", "refused", message);
        SpawnRequests.install(core);

        SpawnHandle handle = new StubCtx(false, 42).spawnBounded("worker", PAYLOAD);

        assertThatThrownBy(handle::tryResult)
                .isInstanceOf(SpawnException.Refused.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("each failure kind is its own exception")
    void eachFailureKindIsItsOwnException() {
        Map<String, Class<? extends SpawnException>> kinds = Map.of(
                "factory_unknown", SpawnException.FactoryUnknown.class,
                "factory_not_bounded", SpawnException.FactoryNotBounded.class,
                "timeout", SpawnException.Timeout.class,
                "unavailable", SpawnException.Unavailable.class);
        SpawnRequests.install(core);
        kinds.forEach((kind, type) -> {
            core.answer = failure("", kind, "the core's sentence for " + kind);
            assertThatThrownBy(new StubCtx(false, 42).spawnBounded("worker", PAYLOAD)::await)
                    .isInstanceOf(type)
                    .hasMessage("the core's sentence for " + kind);
        });
    }

    @Test
    @DisplayName("tryResult answers pending without waiting until the answer arrives")
    void tryResultAnswersPendingUntilTheAnswerArrives() throws Exception {
        SpawnRequests.install(core);
        SpawnHandle handle = new StubCtx(false, 42).spawnBounded("worker", PAYLOAD);

        assertThat(handle.tryResult()).isEmpty();

        core.answer = "{\"id\":\"worker-k7m2qzxw4pd3fn2a\",\"outcome\":\"spawned\"}";
        assertThat(handle.tryResult()).contains(new SpawnResult("worker-k7m2qzxw4pd3fn2a", SpawnOutcome.SPAWNED));
        assertThat(core.reads).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("await leaves the deadline to the core, and every read asks the core")
    void awaitLeavesTheDeadlineToTheCoreAndEveryReadAsksTheCore() throws Exception {
        core.answer = "{\"id\":\"w1\",\"outcome\":\"spawned\"}";
        SpawnRequests.install(core);
        SpawnHandle handle = new StubCtx(false, 42).spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1"));

        assertThat(handle.await()).isEqualTo(handle.await());
        assertThat(handle.tryResult()).contains(new SpawnResult("w1", SpawnOutcome.SPAWNED));

        assertThat(core.reads).containsExactly(Long.MAX_VALUE, Long.MAX_VALUE, 0L);
    }

    @Test
    @DisplayName("a replayed spawn carries the flag and answers skipped with its id, or an empty one without")
    void aReplayedSpawnAnswersSkippedWithItsIdOrAnEmptyOne() throws Exception {
        SpawnRequests.install(core);
        TaskletCtx replaying = new StubCtx(true, 42);

        core.answer = "{\"id\":\"\",\"outcome\":\"skipped_on_replay\"}";
        assertThat(replaying.spawnBounded("worker", PAYLOAD).await())
                .isEqualTo(new SpawnResult("", SpawnOutcome.SKIPPED_ON_REPLAY));

        core.answer = "{\"id\":\"w1\",\"outcome\":\"skipped_on_replay\"}";
        assertThat(replaying
                        .spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1"))
                        .await())
                .isEqualTo(new SpawnResult("w1", SpawnOutcome.SKIPPED_ON_REPLAY));

        assertThat(core.requests).containsExactly("42/worker//p/deliver/true", "42/worker/w1/p/deliver/true");
    }

    @Test
    @DisplayName("without a control plane every read answers unavailable, the call still returns, and one line says so")
    void withoutAControlPlaneEveryReadAnswersUnavailable() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        SpawnHandle handle = capturingStderr(
                stderr, () -> new StubCtx(false, 42).spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1")));

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .isEqualTo("[flezha] spawnBounded(factory `worker`, id `w1`) failed: no control plane to request a"
                        + " spawn on" + System.lineSeparator());
        assertThatThrownBy(handle::tryResult).isInstanceOf(SpawnException.Unavailable.class);
        assertThatThrownBy(handle::await)
                .isInstanceOf(SpawnException.Unavailable.class)
                .hasMessage("no control plane to request a spawn on");
    }

    @Test
    @DisplayName(
            "without a control plane a replayed spawn answers skipped with its id, or an empty one, and logs nothing")
    void withoutAControlPlaneAReplayedSpawnAnswersSkipped() throws Exception {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        TaskletCtx replaying = new StubCtx(true, 42);
        SpawnHandle unnamed = capturingStderr(stderr, () -> replaying.spawnBounded("worker", PAYLOAD));
        SpawnHandle named =
                capturingStderr(stderr, () -> replaying.spawnBounded("worker", PAYLOAD, SpawnOptions.id("w1")));

        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(unnamed.tryResult()).contains(new SpawnResult("", SpawnOutcome.SKIPPED_ON_REPLAY));
        assertThat(named.await()).isEqualTo(new SpawnResult("w1", SpawnOutcome.SKIPPED_ON_REPLAY));
    }

    @Test
    @DisplayName("an answer this SDK cannot read is refused naming it")
    void anUnreadableAnswerIsRefusedNamingIt() {
        core.answer = "{\"id\":\"w1\",\"outcome\":\"hatched\"}";
        SpawnRequests.install(core);

        assertThatThrownBy(new StubCtx(false, 42).spawnBounded("worker", PAYLOAD)::await)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hatched");
    }

    @Test
    @DisplayName("the policy is carried on the wire as the controller reads it")
    void thePolicyIsCarriedOnTheWire() {
        assertThat(IfRunning.DELIVER.wire()).isEqualTo("deliver");
        assertThat(IfRunning.ERROR.wire()).isEqualTo("error");
    }

    private static String failure(String id, String kind, String message) {
        return "{\"id\":\"" + id + "\",\"error\":\"" + kind + "\",\"message\":\"" + message + "\"}";
    }

    private static SpawnHandle capturingStderr(ByteArrayOutputStream into, Supplier<SpawnHandle> spawn) {
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(into, true, StandardCharsets.UTF_8));
        try {
            return spawn.get();
        } finally {
            System.setErr(stderr);
        }
    }

    /**
     * The core's two entries: every request recorded, and a handle's reads answering {@link #answer}
     * until one is non-null, then that same answer, as the core does.
     */
    private static final class FakeCore implements SpawnRequests.Requester {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Long> reads = new CopyOnWriteArrayList<>();
        final Map<Long, String> resolved = new ConcurrentHashMap<>();
        volatile String answer;

        @Override
        public long request(long token, String factoryId, String id, byte[] payload, String ifRunning, boolean replay) {
            requests.add(String.join(
                    "/",
                    String.valueOf(token),
                    factoryId,
                    id,
                    new String(payload, StandardCharsets.UTF_8),
                    ifRunning,
                    String.valueOf(replay)));
            return requests.size();
        }

        @Override
        public String result(long handle, long timeoutMs) {
            reads.add(timeoutMs);
            return resolved.computeIfAbsent(handle, pending -> answer);
        }
    }

    /** Only the members the defaults of {@code spawnBounded} and {@code complete} read. */
    private record StubCtx(boolean replay, long token, List<String> completed) implements TaskletCtx {
        StubCtx(boolean replay, long token) {
            this(replay, token, new CopyOnWriteArrayList<>());
        }

        @Override
        public boolean replaying() {
            return replay;
        }

        @Override
        public long activationToken() {
            return token;
        }

        @Override
        public long now() {
            return 0;
        }

        @Override
        public StableId stableId() {
            return new StableId(0, 0);
        }

        @Override
        public void checkpoint() {}

        @Override
        public void complete(byte[] result) {
            completed.add(new String(result, StandardCharsets.UTF_8));
        }

        @Override
        public <C> void emitKeyed(String key, C value) {}

        @Override
        public <C> void emitKeyedWithMeta(String key, C value, Map<String, String> meta) {}

        @Override
        public <C> void emit(C value) {}

        @Override
        public <C> void emit(String channel, C value) {}

        @Override
        public <C> void emitWithMeta(C value, Map<String, String> meta) {}

        @Override
        public <C> void emitWithMeta(String channel, C value, Map<String, String> meta) {}
    }
}
