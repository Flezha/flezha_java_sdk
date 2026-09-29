package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.RepositoryFixture;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code HostClassDecl} JSON builder — the successor of the retired
 * {@code AddOperatorResponse}-metadata declarations: what the poll core is
 * told about a registered class, with ids equal to declaration order.
 */
class HostClassDeclarationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
        BoundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("an unbounded declaration emits channels and tasklets in id order with tier facts")
    void unboundedDeclaration() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(TierOperator.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_tier_op"));

        assertThat(decl.get("factory_id").asText()).isEqualTo("decl_tier_op");
        assertThat(decl.get("sdk_lang").asInt()).isEqualTo(2);
        assertThat(decl.get("change_format_id").asInt()).isEqualTo(4);
        assertThat(decl.get("effectively_once").asBoolean()).isTrue();
        assertThat(decl.get("checkpoint_ack").asBoolean())
                .as("the RAW flag the author set; the core derives the granularity from it")
                .isTrue();
        assertThat(decl.has("ack_granularity"))
                .as("the granularity is the core's answer, never the builder's claim")
                .isFalse();
        assertThat(decl.has("checkpoint_every"))
                .as("a bare checkpointAck() declares no interval options")
                .isFalse();
        assertThat(decl.has("checkpoint_outputs")).isFalse();
        assertThat(decl.has("checkpoint_max_delay_ms")).isFalse();
        assertThat(decl.has("correction_window_ms")).isFalse();
        assertThat(decl.get("persistent").asBoolean())
                .as("the tier IS durable state, so the class journals a Change")
                .isTrue();
        assertThat(decl.has("bounded")).isFalse();

        JsonNode channels = decl.get("channels");
        assertThat(channels).hasSize(2);
        assertThat(channels.get(0).get("name").asText()).isEqualTo("in");
        assertThat(channels.get(0).get("kind").asText()).isEqualTo("entry");
        assertThat(channels.get(0).has("consumers"))
                .as("consumers are derived from the chains, never declared")
                .isFalse();
        // The chain the entry handle was given: one `into` closing onto
        // tasklet 0. What it MEANS is the core's answer.
        JsonNode entryChain = channels.get(0).get("chains").get(0);
        assertThat(entryChain.get(0).get("link").asText()).isEqualTo("into");
        assertThat(entryChain.get(0).get("tasklet").asInt()).isZero();
        assertThat(channels.get(0).has("producer")).isFalse();
        assertThat(channels.get(1).get("kind").asText())
                .as("the plugin kind is derived; the record states what was declared")
                .isEqualTo("inter_task");
        assertThat(channels.get(1).get("chains").get(0).get(0).get("link").asText())
                .isEqualTo("sink");
        assertThat(channels.get(1).get("producer").asInt()).isEqualTo(0);
        assertThat(channels.get(1).get("keyed").asBoolean()).isTrue();
        assertThat(channels.get(0).get("overflow").asText())
                .as("nothing drops by default: an entry channel discards nothing")
                .isEqualTo("drop_nothing");

        JsonNode tasklets = decl.get("tasklets");
        assertThat(tasklets).hasSize(1);
        assertThat(tasklets.get(0).get("name").asText()).isEqualTo("decl_task");
        assertThat(tasklets.get(0).has("cell_mode"))
                .as("single mode is the default")
                .isFalse();
        assertThat(tasklets.get(0).get("clock").asText()).isEqualTo("wall");
        assertThat(tasklets.get(0).has("change_is_nochange"))
                .as("this tasklet journals a Change, so a logical clock over it would advance")
                .isFalse();
        assertThat(tasklets.get(0).get("mailbox_capacity").asInt()).isPositive();
    }

    @Test
    @DisplayName("each Tasklet declares its own schema version, never the class maximum")
    void perTaskletSchemaVersions() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(MixedVersionOperator.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_mixed_version_op"));

        assertThat(decl.has("schema_version"))
                .as("a class-wide version would re-stamp a Tasklet with a version it never declared")
                .isFalse();

        JsonNode tasklets = decl.get("tasklets");
        assertThat(tasklets).hasSize(2);
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        tasklets.forEach(p -> byName.put(p.get("name").asText(), p));

        assertThat(byName.get("v2_task").get("schema_version").asInt()).isEqualTo(2);
        assertThat(byName.get("v2_task").get("min_readable_schema_version").asInt())
                .isEqualTo(1);
        assertThat(byName.get("v1_task").get("schema_version").asInt())
                .as("the sibling keeps its own version when v2_task is bumped")
                .isEqualTo(1);
        assertThat(byName.get("v1_task").get("min_readable_schema_version").asInt())
                .isEqualTo(1);
        assertThat(byName.get("v1_task").get("change_is_nochange").asBoolean())
                .as("a Void-state tasklet states it, so the core can refuse a logical clock over it")
                .isTrue();
    }

    @Test
    @DisplayName("a bounded declaration carries its tasklets with the bounded block")
    void boundedDeclaration() throws Exception {
        BoundedOperatorClassRegistry.registerClass(DeclBounded.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_bounded"));

        assertThat(decl.get("factory_id").asText()).isEqualTo("decl_bounded");
        assertThat(decl.get("channels")).isNotEmpty();
        assertThat(decl.get("tasklets")).hasSize(1);
        assertThat(decl.get("tasklets").get(0).get("name").asText()).isEqualTo("v1_task");
        assertThat(decl.get("persistent").asBoolean()).isTrue();

        JsonNode bounded = decl.get("bounded");
        assertThat(bounded.get("loop_mode").asText()).isEqualTo("once");
        assertThat(bounded.get("rollback_mode").asText()).isEqualTo("all_or_nothing");
        assertThat(bounded.get("max_duration_ms").asLong()).isEqualTo(30_000);
        assertThat(bounded.fieldNames())
                .toIterable()
                .as("retry is the edge's, carried on each channel")
                .doesNotContain("retry_attempts", "retry_backoff_ms", "barrier_retry_attempts");
        assertThat(decl.has("sync_durability"))
                .as("asynchronous remote durability is the cost-free default")
                .isFalse();
    }

    @Test
    @DisplayName("an unbounded operator's opt-out is read from the class, not its Tasklets")
    void anUnboundedOptsOutByItsSwitch() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(PersistenceOffOperator.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_persistence_off_op"));

        assertThat(decl.path("persistent").asBoolean())
                .as("persistenceEnabled() false opts out even over a tasklet that journals a Change")
                .isFalse();
        assertThat(decl.path("sleep_when_idle").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("sleeping is a class setting of both kinds, on for a durable bounded by default")
    void sleepWhenIdleIsDeclaredForBothKinds() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(SleepingOperator.class);
        BoundedOperatorClassRegistry.registerClass(DeclBounded.class);
        BoundedOperatorClassRegistry.registerClass(OptedOutBounded.class);

        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_sleeping_op"))
                        .path("sleep_when_idle")
                        .asBoolean())
                .isTrue();
        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_bounded"))
                        .path("sleep_when_idle")
                        .asBoolean())
                .as("a bounded sleeps whenever it persists")
                .isTrue();
        JsonNode optedOut = JSON.readTree(HostClassDeclaration.forFactory("decl_opted_out_bounded"));
        assertThat(optedOut.path("persistent").asBoolean()).isFalse();
        assertThat(optedOut.path("sleep_when_idle").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("sleeping without persistence is refused by name, for either kind")
    void sleepingWithoutPersistenceIsRefused() {
        Refused.by(
                "topology.sleep_without_persistence",
                () -> UnboundedOperatorClassRegistry.registerClass(SleeplessUnboundedOperator.class));
        Refused.by(
                "topology.sleep_without_persistence",
                () -> BoundedOperatorClassRegistry.registerClass(SleeplessBounded.class));
    }

    @Test
    @DisplayName("a bounded class declaring synchronous durability ships the flag")
    void syncDurabilityDeclaration() throws Exception {
        BoundedOperatorClassRegistry.registerClass(SyncDurableBounded.class);

        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_sync_bounded"));
        assertThat(decl.get("sync_durability").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a declared hook needs no second declaration: a written compensate is compensable")
    void aWrittenCompensateIsDeclared() throws Exception {
        BoundedOperatorClassRegistry.registerClass(FullyDeclaredBounded.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_full_bounded"));

        assertThat(decl.get("tasklets").get(0).path("compensate_overridden").asBoolean())
                .as("the rollback plans only from compensable tasklets")
                .isTrue();
    }

    @Test
    @DisplayName("the builder can spell every key the shared fixture pins")
    void everyPinnedDeclarationKeyHasAJavaSpelling() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(FullyDeclaredOperator.class);
        UnboundedOperatorClassRegistry.registerClass(FullyDeclaredDynamicOperator.class);
        BoundedOperatorClassRegistry.registerClass(FullyDeclaredBounded.class);

        JsonNode stream = JSON.readTree(HostClassDeclaration.forFactory("decl_full_op"));
        JsonNode dynamic = JSON.readTree(HostClassDeclaration.forFactory("decl_full_dynamic_op"));
        JsonNode bounded = JSON.readTree(HostClassDeclaration.forFactory("decl_full_bounded"));

        // A key may live on any node of its section: the entry channel has no
        // `producer`, the plugin-bound one has the `keyed` flag.
        assertEverySpellingExists("class", List.of(stream), Set.of("sync_durability", "bounded"));
        assertEverySpellingExists("channel", nodes(stream.get("channels")), Set.of());
        // Cell mode splits the tasklet keys: a static tasklet declares its
        // key set, a dynamic one its TTL and cap. Both modes together are the
        // section.
        List<JsonNode> tasklets = new ArrayList<>(nodes(stream.get("tasklets")));
        tasklets.addAll(nodes(dynamic.get("tasklets")));
        assertEverySpellingExists("tasklet", tasklets, Set.of());
        assertEverySpellingExists("bounded", List.of(bounded.get("bounded")), Set.of());
        assertThat(bounded.has("sync_durability")).isTrue();
    }

    private static List<JsonNode> nodes(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /**
     * Every key the fixture pins for {@code section} that no node of that
     * section carries, minus the ones this declaration legitimately omits (a
     * static tasklet has no {@code idle_ttl_ms}, an unbounded class no
     * {@code sync_durability}). A key with no Java spelling at all shows up
     * here and nowhere else.
     */
    private static void assertEverySpellingExists(
            String section, List<JsonNode> sectionNodes, Set<String> legitimatelyAbsent) throws Exception {
        List<String> missing = new ArrayList<>();
        for (String key : pinnedKeys(section)) {
            boolean present = sectionNodes.stream().anyMatch(node -> node.has(key));
            if (!present && !legitimatelyAbsent.contains(key)) {
                missing.add(key);
            }
        }
        assertThat(missing)
                .as(
                        "%s keys pinned by %s that the Java builder never emits — teach "
                                + "HostClassDeclaration and the authoring surface, or the core and Java "
                                + "have drifted apart",
                        section, KEY_FIXTURE)
                .isEmpty();
    }

    private static final String KEY_FIXTURE = "test/fixtures/host_class_decl_keys.txt";

    private static List<String> pinnedKeys(String section) throws Exception {
        String prefix = section + ".";
        return RepositoryFixture.lines(KEY_FIXTURE).stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    /** Declares every unbounded-side option the Java surface has a spelling for. */
    public static final class FullyDeclaredOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_full_op";
        }

        @Override
        public boolean sleepWhenIdle() {
            return true;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.checkpointAck();
            c.checkpointEvery(500);
            c.outputsAtCheckpoint(50);
            c.correctionWindow(600_000);
            c.beyondWindowPolicy(com.flezha.sdk.channel.BeyondWindowPolicy.DROP);
            c.input(String.class, "in")
                    .onFull(com.flezha.sdk.channel.OverflowPolicy.DROP_NOTHING)
                    .retryMax(3)
                    .retryBackoffMs(300)
                    .into(FullyDeclaredTask.class)
                    .keyed()
                    .sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The dynamic half of the tasklet surface: a lazy Cell with a TTL and a cap. */
    public static final class FullyDeclaredDynamicOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_full_dynamic_op";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .keyBy(input -> input.split(":")[0])
                    .into(FullyDeclaredDynamicTask.class)
                    .sink("out", s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Tasklet.Spec(name = "decl_full_dynamic_task", maxCells = 4096, idleTtlSeconds = 200)
    public static final class FullyDeclaredDynamicTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    /** A static, compensating, persistent tasklet: the widest tasklet declaration. */
    @Tasklet.Spec(
            name = "decl_full_task",
            keys = {"a", "b"},
            compactAfterBytes = 4096)
    public static final class FullyDeclaredTask implements Tasklet<String, FullyDeclaredTask.State, String> {

        public static final class State {
            public int seen;
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(State.class, DeclChange.class);
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of("a");
        }

        // `correctionWindow` needs an event-time clock: a correction rewinds to a
        // baseline before the late input's event time.
        @Override
        public long eventTime(String input) {
            return Long.parseLong(input.split(":")[0]);
        }

        @Override
        public void run(String input, Ctx<State, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.change(new DeclChange(input));
            ctx.emitKeyed("a", input);
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<State, String> ctx) {}
    }

    public record DeclChange(String value) implements Change<FullyDeclaredTask.State> {
        @Override
        public void applyTo(FullyDeclaredTask.State state) {
            state.seen++;
        }
    }

    /** Declares every bounded-side option the Java surface has a spelling for. */
    public static final class FullyDeclaredBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_full_bounded";
        }

        @Override
        public boolean syncDurability() {
            return true;
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(30));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(UndoTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("forFactory resolves both registries and refuses an unknown id")
    void forFactoryResolvesRegistries() {
        UnboundedOperatorClassRegistry.registerClass(TierOperator.class);
        BoundedOperatorClassRegistry.registerClass(DeclBounded.class);
        assertThat(HostClassDeclaration.forFactory("decl_tier_op")).isNotEmpty();
        assertThat(HostClassDeclaration.forFactory("decl_bounded")).isNotEmpty();
        assertThatThrownBy(() -> HostClassDeclaration.forFactory("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("the checkpoint interval options ride the declaration only when declared")
    void checkpointIntervalOptions() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(BatchedOutputsOperator.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_batched_op"));

        assertThat(decl.has("checkpoint_ack"))
                .as("each option writes its OWN raw flag; the core implies the granularity")
                .isFalse();
        assertThat(decl.get("checkpoint_every").asInt()).isEqualTo(500);
        assertThat(decl.get("checkpoint_outputs").asText()).isEqualTo("at_checkpoint");
        assertThat(decl.get("checkpoint_max_delay_ms").asLong()).isEqualTo(50L);
    }

    @Test
    @DisplayName("an interval option without the tier is refused, naming the missing tier")
    void checkpointOptionsWithoutTier() {
        Refused.by(
                "topology.checkpoint_ack_without_tier",
                () -> UnboundedOperatorClassRegistry.registerClass(NoTierBatchedOperator.class));
    }

    @Test
    @DisplayName("A durable chained loop whose terminal task releases nothing is refused")
    void chainedLoopTerminalWithoutHandoffIsRefusedByTheCore() {
        // The refusal is the core's, raised where every host reaches it —
        // declareClass — and is not restated in this SDK, exactly as the
        // branching-chained-loop refusal already is. Only an inter-tasklet
        // channel carries a journal codec, so a terminal whose only wiring is a
        // plugin sink ships an empty handoff and the crash-resumed loop reads
        // that as convergence.
        BoundedOperatorClassRegistry.registerClass(ChainedTerminalIntoSinkBounded.class);
        byte[] declaration = HostClassDeclaration.forFactory("decl_chained_sink");

        long ctx = FlzCore.newContext();
        try {
            FlezhaException refused = Refused.by(
                    "topology.chained_loop_terminal_without_handoff", () -> FlzCore.declareClass(ctx, declaration));
            assertThat(Refused.details(refused, "topology.chained_loop_terminal_without_handoff"))
                    .containsEntry("tasklet", "decl_chained_sink_task");
        } finally {
            FlzCore.contextFree(ctx);
        }
    }

    /** A durable CHAINED loop whose terminal task's only output is a plugin sink. */
    public static final class ChainedTerminalIntoSinkBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_chained_sink";
        }

        @Override
        public LoopMode loopMode() {
            return LoopMode.CHAINED;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(ChainedSinkTask.class).sink("out");
        }
    }

    @Tasklet.Spec(name = "decl_chained_sink_task")
    public static final class ChainedSinkTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    public static final class BatchedOutputsOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_batched_op";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.checkpointEvery(500);
            c.outputsAtCheckpoint(50);
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class NoTierBatchedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_no_tier_batched_op";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.outputsAtCheckpoint(50);
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class TierOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_tier_op";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.checkpointAck();
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A tier-less class whose tasklet journals a Change, opted out by its switch. */
    public static final class PersistenceOffOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_persistence_off_op";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class SleepingOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_sleeping_op";
        }

        @Override
        public boolean sleepWhenIdle() {
            return true;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class SleeplessUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_sleepless_op";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public boolean sleepWhenIdle() {
            return true;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Two tasklets in one class, each at its own schema version. */
    public static final class MixedVersionOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "decl_mixed_version_op";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .into(VersionTwoTask.class)
                    .named("mid")
                    .into(VersionOneTask.class)
                    .sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Tasklet.Spec(name = "v2_task", schemaVersion = 2, minReadableSchemaVersion = 1)
    public static final class VersionTwoTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "v1_task")
    public static final class VersionOneTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    public static final class SyncDurableBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_sync_bounded";
        }

        @Override
        public boolean syncDurability() {
            return true;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(VersionOneTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The tier fixtures' tasklet. It declares a state because
     * {@code effectivelyOnce()} requires one: the tier IS durable state.
     */
    @Tasklet.Spec(name = "decl_task")
    public static final class DeclTask implements Tasklet<String, DeclTask.Kept, String> {
        public static final class Kept {}

        @Override
        public void run(String input, Ctx<Kept, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emitKeyed("k", input);
        }
    }

    @Tasklet.Spec(name = "undo_task")
    public static final class UndoTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<Void, String> ctx) {}
    }

    public static final class DeclBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_bounded";
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(30));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(VersionOneTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class OptedOutBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_opted_out_bounded";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(30));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(VersionOneTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class SleeplessBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "decl_sleepless_bounded";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public boolean sleepWhenIdle() {
            return true;
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(30));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(VersionOneTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
