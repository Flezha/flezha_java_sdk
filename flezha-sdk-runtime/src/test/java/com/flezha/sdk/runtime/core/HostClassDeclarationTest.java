package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.RepositoryFixture;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
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
 * {@code AddRelayResponse}-metadata declarations: what the poll core is
 * told about a registered class, with ids equal to declaration order.
 */
class HostClassDeclarationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void reset() {
        RelayClassRegistry.reset();
    }

    @Test
    @DisplayName("a flow declaration emits its settings tree, then channels and tasklets in id order")
    void flowDeclaration() throws Exception {
        RelayClassRegistry.registerClass(TierRelay.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_tier_op"));

        assertThat(decl.get("factory_id").asText()).isEqualTo("decl_tier_op");
        assertThat(decl.get("sdk_lang").asInt()).isEqualTo(2);
        assertThat(decl.get("change_format_id").asInt()).isEqualTo(4);
        assertThat(decl.get("run"))
                .as("the tree as the author wrote it; the core derives the granularity from it")
                .isEqualTo(
                        JSON.readTree(
                                """
                        {"flow": {"exactly": {"effectively_once": {
                            "ack": {"checkpoint": {"outputs": "as_produced"}},
                            "beyond_window": "process_as_arrival"}}}}
                        """));
        assertThat(decl.get("durable"))
                .as("kept locally, sleeping as the run does, when the class says nothing")
                .isEqualTo(JSON.readTree("\"local\""));
        assertThat(decl.has("description")).isFalse();
        assertThat(decl.has("max_duration_ms")).isFalse();

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
        RelayClassRegistry.registerClass(MixedVersionRelay.class);
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
    @DisplayName("a steps declaration carries its run, its wall-clock stop and its tasklets")
    void stepsDeclaration() throws Exception {
        RelayClassRegistry.registerClass(DeclSteps.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_steps"));

        assertThat(decl.get("factory_id").asText()).isEqualTo("decl_steps");
        assertThat(decl.get("tasklets").get(0).get("name").asText()).isEqualTo("v1_task");
        assertThat(decl.get("run"))
                .isEqualTo(JSON.readTree("{\"steps\": {\"each\": \"once\", \"on_failure\": \"undo_all\"}}"));
        assertThat(decl.get("max_duration_ms").asLong()).isEqualTo(30_000);
        assertThat(decl.get("durable")).isEqualTo(JSON.readTree("\"local\""));
    }

    @Test
    @DisplayName(
            "Default-on with no override: a class that says nothing is kept locally and leaves its sleep to its run")
    void defaultOnWithNoOverride() throws Exception {
        RelayClassRegistry.registerClass(MixedVersionRelay.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_mixed_version_op"));

        assertThat(decl.get("durable")).isEqualTo(JSON.readTree("\"local\""));
        assertThat(decl.get("run")).isEqualTo(JSON.readTree("{\"flow\": {\"exactly\": \"at_least_once\"}}"));
    }

    @Test
    @DisplayName("Code-declared opt-out is honored")
    void aFlowRelaysOptOutIsReadFromTheClassNotItsTasklets() throws Exception {
        // DeclTask keeps a state; the class's own durable() still decides.
        RelayClassRegistry.registerClass(InMemoryRelay.class);
        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_in_memory_op"))
                        .get("durable"))
                .isEqualTo(JSON.readTree("false"));
    }

    @Test
    @DisplayName("durable spells each level: false, local and remote, with or without sleeping")
    void durableSpellsEachLevel() throws Exception {
        RelayClassRegistry.registerClass(InMemoryRelay.class);
        RelayClassRegistry.registerClass(QuickSleeperRelay.class);
        RelayClassRegistry.registerClass(RemoteSteps.class);

        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_in_memory_op"))
                        .get("durable"))
                .isEqualTo(JSON.readTree("false"));
        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_quick_sleeper_op"))
                        .get("durable"))
                .isEqualTo(JSON.readTree("{\"local\": {\"sleep_after\": 600000}}"));
        assertThat(JSON.readTree(HostClassDeclaration.forFactory("decl_remote_steps"))
                        .get("durable"))
                .as("a remote class that never sleeps")
                .isEqualTo(JSON.readTree("{\"remote\": {\"sleep_after\": false}}"));
    }

    @Test
    @DisplayName("the effectively-once tier in memory is refused by name: the guarantee needs a disk")
    void theTierInMemoryIsRefused() {
        Refused.by(
                "topology.effectively_once_without_persistence",
                () -> RelayClassRegistry.registerClass(TierInMemoryRelay.class));
    }

    @Test
    @DisplayName("a declared hook needs no second declaration: a written compensate is compensable")
    void aWrittenCompensateIsDeclared() throws Exception {
        RelayClassRegistry.registerClass(FullyDeclaredSteps.class);
        JsonNode decl = JSON.readTree(HostClassDeclaration.forFactory("decl_full_steps"));

        assertThat(decl.get("tasklets").get(0).path("compensate_overridden").asBoolean())
                .as("the rollback plans only from compensable tasklets")
                .isTrue();
    }

    @Test
    @DisplayName("the builder can spell every key the shared fixture pins")
    void everyPinnedDeclarationKeyHasAJavaSpelling() throws Exception {
        RelayClassRegistry.registerClass(FullyDeclaredRelay.class);
        RelayClassRegistry.registerClass(FullyDeclaredDynamicRelay.class);

        JsonNode flow = JSON.readTree(HostClassDeclaration.forFactory("decl_full_op"));
        JsonNode dynamic = JSON.readTree(HostClassDeclaration.forFactory("decl_full_dynamic_op"));

        // A key may live on any node of its section: the entry channel has no
        // `producer`, the plugin-bound one has the `keyed` flag.
        assertEverySpellingExists("class", List.of(flow), Set.of());
        assertEverySpellingExists("channel", nodes(flow.get("channels")), Set.of());
        // Cell mode splits the tasklet keys: a static tasklet declares its
        // key set, a dynamic one its TTL and cap. Both modes together are the
        // section.
        List<JsonNode> tasklets = new ArrayList<>(nodes(flow.get("tasklets")));
        tasklets.addAll(nodes(dynamic.get("tasklets")));
        assertEverySpellingExists("tasklet", tasklets, Set.of());
    }

    @Test
    @DisplayName("forFactory resolves the registry and refuses an unknown id")
    void forFactoryResolvesTheRegistry() {
        RelayClassRegistry.registerClass(TierRelay.class);
        RelayClassRegistry.registerClass(DeclSteps.class);
        assertThat(HostClassDeclaration.forFactory("decl_tier_op")).isNotEmpty();
        assertThat(HostClassDeclaration.forFactory("decl_steps")).isNotEmpty();
        assertThatThrownBy(() -> HostClassDeclaration.forFactory("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("the checkpoint members ride the tree only when declared")
    void checkpointMembers() throws Exception {
        RelayClassRegistry.registerClass(BatchedOutputsRelay.class);
        JsonNode ack = JSON.readTree(HostClassDeclaration.forFactory("decl_batched_op"))
                .at("/run/flow/exactly/effectively_once/ack");

        assertThat(ack)
                .isEqualTo(JSON.readTree(
                        "{\"checkpoint\": {\"every\": 500, \"max_delay_ms\": 50, \"outputs\": \"at_checkpoint\"}}"));
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
        RelayClassRegistry.registerClass(ChainedTerminalIntoSinkSteps.class);
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

    private static List<JsonNode> nodes(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /**
     * Every key the fixture pins for {@code section} that no node of that
     * section carries, minus the ones this declaration legitimately omits (a
     * static tasklet has no {@code idle_ttl_ms}). A key with no Java spelling at all shows up
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

    /** Declares every flow-side option the Java surface has a spelling for. */
    public static final class FullyDeclaredRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_full_op";
        }

        @Override
        public String description() {
            return "every key the fixture pins";
        }

        @Override
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofMinutes(5));
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce()
                    .withAck(new Ack.Checkpoint()
                            .withEvery(500)
                            .withMaxDelayMs(50)
                            .withOutputs(Outputs.AT_CHECKPOINT))
                    .withCorrectionMs(600_000)
                    .withBeyondWindow(BeyondWindow.DROP));
        }

        @Override
        public void channels(ChannelBuilder c) {
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
    public static final class FullyDeclaredDynamicRelay implements Relay {
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

        // A correction window needs an event-time clock: a correction rewinds to a
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

    /** A steps class whose tasklet writes its compensate. */
    public static final class FullyDeclaredSteps implements Relay {
        @Override
        public String factoryId() {
            return "decl_full_steps";
        }

        @Override
        public Run run() {
            return new Run.Steps(Each.PER_INPUT, OnFailure.KEEP_PROGRESS);
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(UndoTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A durable CHAINED loop whose terminal task's only output is a plugin sink. */
    public static final class ChainedTerminalIntoSinkSteps implements Relay {
        @Override
        public String factoryId() {
            return "decl_chained_sink";
        }

        @Override
        public Run run() {
            return Run.steps(Each.CHAINED);
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

    public static final class BatchedOutputsRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_batched_op";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce()
                    .withAck(new Ack.Checkpoint()
                            .withEvery(500)
                            .withMaxDelayMs(50)
                            .withOutputs(Outputs.AT_CHECKPOINT)));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class TierRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_tier_op";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce().withAck(new Ack.Checkpoint()));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class TierInMemoryRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_tier_in_memory_op";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce());
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A class whose tasklet journals a Change, kept in memory by its setting. */
    public static final class InMemoryRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_in_memory_op";
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class QuickSleeperRelay implements Relay {
        @Override
        public String factoryId() {
            return "decl_quick_sleeper_op";
        }

        @Override
        public Durable durable() {
            return new Durable.Local(Duration.ofMinutes(10));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(DeclTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class RemoteSteps implements Relay {
        @Override
        public String factoryId() {
            return "decl_remote_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Remote(new Durable.SleepAfter.Never());
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(VersionOneTask.class).sink(s -> s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Two tasklets in one class, each at its own schema version. */
    public static final class MixedVersionRelay implements Relay {
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

    /** The tier fixtures' tasklet. It declares a state, because the tier IS durable state. */
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

    public static final class DeclSteps implements Relay {
        @Override
        public String factoryId() {
            return "decl_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
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
