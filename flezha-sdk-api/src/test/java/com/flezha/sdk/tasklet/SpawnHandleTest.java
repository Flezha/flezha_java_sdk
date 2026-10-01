package com.flezha.sdk.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
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
                new StubCtx(false, 42).spawnSteps("worker", PAYLOAD).await();

        assertThat(result).isEqualTo(new SpawnResult("worker-k7m2qzxw4pd3fn2a", SpawnOutcome.SPAWNED));
        assertThat(core.requests).containsExactly("42/worker//p/deliver/false");
    }

    @Test
    @DisplayName("a spawn with an id carries it and delivers by default; the error policy is carried too")
    void aSpawnWithAnIdCarriesItAndItsPolicy() throws Exception {
        core.answer = "{\"id\":\"w1\",\"outcome\":\"delivered\"}";
        SpawnRequests.install(core);
        Ctx<Void, Object> ctx = new StubCtx(false, 42);

        assertThat(ctx.spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1")).await())
                .isEqualTo(new SpawnResult("w1", SpawnOutcome.DELIVERED));
        ctx.spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1").ifRunning(IfRunning.ERROR));

        assertThat(core.requests).containsExactly("42/worker/w1/p/deliver/false", "42/worker/w1/p/error/false");
    }

    record Ticket(String symbol, int quantity) {}

    @Test
    @DisplayName("a payload is encoded as a sink with no encoder encodes it: a string as UTF-8, a record as JSON")
    void aPayloadIsEncodedAsASinkWithNoEncoder() {
        SpawnRequests.install(core);
        Ctx<Void, Object> ctx = new StubCtx(false, 42);

        ctx.spawnSteps("worker", "p");
        ctx.spawnSteps("worker", new Ticket("AAPL", 10), SpawnOptions.id("w1"));

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
    @DisplayName("the error policy comes back as already running, carrying the controller's code")
    void theErrorPolicyComesBackAsAlreadyRunning() {
        core.refusal = FlezhaException.of("operator.already_running", Map.of("operator", "w1"));
        SpawnRequests.install(core);

        SpawnHandle handle = new StubCtx(false, 42)
                .spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1").ifRunning(IfRunning.ERROR));

        assertThatThrownBy(handle::await).isSameAs(core.refusal);
    }

    @Test
    @DisplayName("a refusal does not fault the call; a read reports it with the controller's code")
    void aRefusalIsReportedOnlyByARead() {
        core.refusal = FlezhaException.of("quota.exceeded", Map.of("kind", "max_operators", "current", 3, "limit", 3));
        SpawnRequests.install(core);

        SpawnHandle handle = new StubCtx(false, 42).spawnSteps("worker", PAYLOAD);

        assertThatThrownBy(handle::tryResult).isInstanceOfSatisfying(FlezhaException.class, e -> {
            assertThat(e.code()).isEqualTo("quota.exceeded");
            assertThat(e.details()).containsEntry("limit", 3);
        });
    }

    @Test
    @DisplayName("each failure is the one exception, told apart by its code")
    void eachFailureIsTheOneExceptionToldApartByItsCode() {
        SpawnRequests.install(core);
        for (String code : List.of(
                "operator.factory_unknown", "operator.factory_not_steps", "spawn.timeout", "spawn.unavailable")) {
            core.refusal = FlezhaException.of(code, Map.of());
            assertThatThrownBy(new StubCtx(false, 42).spawnSteps("worker", PAYLOAD)::await)
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(e.code()).isEqualTo(code));
        }
    }

    @Test
    @DisplayName("tryResult answers pending without waiting until the answer arrives")
    void tryResultAnswersPendingUntilTheAnswerArrives() throws Exception {
        SpawnRequests.install(core);
        SpawnHandle handle = new StubCtx(false, 42).spawnSteps("worker", PAYLOAD);

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
        SpawnHandle handle = new StubCtx(false, 42).spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1"));

        assertThat(handle.await()).isEqualTo(handle.await());
        assertThat(handle.tryResult()).contains(new SpawnResult("w1", SpawnOutcome.SPAWNED));

        assertThat(core.reads).containsExactly(Long.MAX_VALUE, Long.MAX_VALUE, 0L);
    }

    @Test
    @DisplayName("a replayed spawn carries the flag and answers skipped with its id, or an empty one without")
    void aReplayedSpawnAnswersSkippedWithItsIdOrAnEmptyOne() throws Exception {
        SpawnRequests.install(core);
        Ctx<Void, Object> replaying = new StubCtx(true, 42);

        core.answer = "{\"id\":\"\",\"outcome\":\"skipped_on_replay\"}";
        assertThat(replaying.spawnSteps("worker", PAYLOAD).await())
                .isEqualTo(new SpawnResult("", SpawnOutcome.SKIPPED_ON_REPLAY));

        core.answer = "{\"id\":\"w1\",\"outcome\":\"skipped_on_replay\"}";
        assertThat(replaying
                        .spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1"))
                        .await())
                .isEqualTo(new SpawnResult("w1", SpawnOutcome.SKIPPED_ON_REPLAY));

        assertThat(core.requests).containsExactly("42/worker//p/deliver/true", "42/worker/w1/p/deliver/true");
    }

    @Test
    @DisplayName("without a control plane every read answers unavailable, the call still returns, and one line says so")
    void withoutAControlPlaneEveryReadAnswersUnavailable() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        SpawnHandle handle = capturingStderr(
                stderr, () -> new StubCtx(false, 42).spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1")));

        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .startsWith("[flezha] spawnSteps(factory `worker`, id `w1`) failed: spawn.unavailable: ")
                .endsWith(System.lineSeparator())
                .hasLineCount(1);
        assertThatThrownBy(handle::tryResult).isInstanceOfSatisfying(FlezhaException.class, e -> assertThat(e.code())
                .isEqualTo("spawn.unavailable"));
        assertThatThrownBy(handle::await).isInstanceOfSatisfying(FlezhaException.class, e -> assertThat(e.code())
                .isEqualTo("spawn.unavailable"));
    }

    @Test
    @DisplayName(
            "without a control plane a replayed spawn answers skipped with its id, or an empty one, and logs nothing")
    void withoutAControlPlaneAReplayedSpawnAnswersSkipped() throws Exception {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Ctx<Void, Object> replaying = new StubCtx(true, 42);
        SpawnHandle unnamed = capturingStderr(stderr, () -> replaying.spawnSteps("worker", PAYLOAD));
        SpawnHandle named =
                capturingStderr(stderr, () -> replaying.spawnSteps("worker", PAYLOAD, SpawnOptions.id("w1")));

        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(unnamed.tryResult()).contains(new SpawnResult("", SpawnOutcome.SKIPPED_ON_REPLAY));
        assertThat(named.await()).isEqualTo(new SpawnResult("w1", SpawnOutcome.SKIPPED_ON_REPLAY));
    }

    @Test
    @DisplayName("an answer this SDK cannot read is refused naming it")
    void anUnreadableAnswerIsRefusedNamingIt() {
        core.answer = "{\"id\":\"w1\",\"outcome\":\"hatched\"}";
        SpawnRequests.install(core);

        assertThatThrownBy(new StubCtx(false, 42).spawnSteps("worker", PAYLOAD)::await)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hatched");
    }

    @Test
    @DisplayName("the policy is carried on the wire as the controller reads it")
    void thePolicyIsCarriedOnTheWire() {
        assertThat(IfRunning.DELIVER.wire()).isEqualTo("deliver");
        assertThat(IfRunning.ERROR.wire()).isEqualTo("error");
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
     * until one is non-null, then that same answer, as the core does. A read throws {@link #refusal}
     * when one is set, as the core throws a failed spawn.
     */
    private static final class FakeCore implements SpawnRequests.Requester {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Long> reads = new CopyOnWriteArrayList<>();
        final Map<Long, String> resolved = new ConcurrentHashMap<>();
        volatile String answer;
        volatile FlezhaException refusal;

        @Override
        public long request(
                long token,
                String factoryId,
                String id,
                byte[] payload,
                String ifRunning,
                boolean replay,
                long offset) {
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
            if (refusal != null) {
                throw refusal;
            }
            return resolved.computeIfAbsent(handle, pending -> answer);
        }
    }

    /** Only the members the defaults of {@code spawnSteps} and {@code complete} read. */
    private record StubCtx(boolean replay, long token, List<String> completed) implements Ctx<Void, Object> {
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
        public Void state() {
            return null;
        }

        @Override
        public void change(com.flezha.sdk.state.Change<Void> change) {}

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
        public void emitKeyed(String key, Object value) {}

        @Override
        public void emitKeyedWithMeta(String key, Object value, Map<String, String> meta) {}

        @Override
        public void emit(Object value) {}

        @Override
        public <C> void emit(String channel, C value) {}

        @Override
        public void emitWithMeta(Object value, Map<String, String> meta) {}

        @Override
        public <C> void emitWithMeta(String channel, C value, Map<String, String> meta) {}

        @Override
        public void part(Object value) {}
    }
}
