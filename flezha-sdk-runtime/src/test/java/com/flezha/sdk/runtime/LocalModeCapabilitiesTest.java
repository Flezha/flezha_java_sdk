package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.CapturedStderr;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.LocalOutput;
import com.flezha.sdk.LocalRelay;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Local mode over the five capabilities the LANGUAGE LAYER owns, each driven through the fixture
 * class the conformance test app's {@code conformance.json} names for it, so what passes here and what
 * passes end to end are not two different graphs.
 *
 * <p>The other ten capabilities in the vocabulary are the controller's: persistence,
 * effectively-once, correction, partitioning, steps relays, liveness, restarts. Local mode refuses them
 * by name rather than approximating them.
 *
 * <p>What each test can assert is what a run with no controller can see. A durable write reaches a
 * null output, a parked payload has nowhere to park, and a partition member's key range arrives with
 * {@code AddRelay}, so the assertions here are the output side of each capability, which is the
 * half the language layer produces.
 *
 * <p>The classes are DECLARED HERE rather than imported, which the other SDKs' local-mode suites do
 * not have to do. The test app is a Maven project of its own that depends on the
 * published SDK, so no SDK module can reach its sources without inverting that dependency. Each
 * declaration therefore carries the test app's factory id and channel names verbatim, and
 * {@link #declares} holds the id against the manifest, so a class the end-to-end renames or drops
 * fails here instead of leaving a second graph nobody else runs. They are stateless where the test
 * app's twins journal a Change per input: the Change is there so the controller writes a WAL record,
 * and a run with no controller has nothing to write it to. The one exception is the tier class,
 * whose declaration the core refuses without it.
 *
 * <p>Release order is asserted per key, not across keys. Java dispatches activations on a worker
 * pool and keeps one Cell on one worker at a time, so the order two keys reach the output in is not
 * something the language layer promises.
 */
class LocalModeCapabilitiesTest {

    private static final Duration BUDGET = Duration.ofSeconds(20);

    /** Each run logs the core's local-mode banner, and nothing else may be logged. */
    private static CapturedStderr stderr;

    @BeforeAll
    static void captureStderr() {
        stderr = CapturedStderr.start();
    }

    @AfterAll
    static void onlyBannersWereLogged() {
        stderr.close();
        assertThat(stderr.lines()).allMatch(line -> line.startsWith("[flezha] LOCAL MODE: "));
    }

    /** Short enough to keep the run brief, long enough not to race the feed that arms it. */
    private static final Duration SESSION_TIMEOUT = Duration.ofMillis(200);

    @Test
    @DisplayName("shorthand: a short form is built and run with no controller")
    void aShortFormIsBuiltAndRunWithNoController() {
        declares("shorthand", "sugar_do_nothing");

        List<LocalOutput> released =
                release(SugarDoNothing.class, "input", "sugar_do_nothing_echo", List.of("alpha", "beta"));

        assertThat(payloads(released))
                .as("the builder produced a graph the core ran, in order")
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("keyed_channels: keyed emit stamps flz.key on the wire, with its Cell and seq")
    void aKeyedOutputStampsTheKeyTheClassResolved() {
        // `partition_pass` is the manifest's only keyed-channel class. Partitioning itself is the
        // controller's (a member's key-group range arrives with `AddRelay`), so what is asserted
        // here is the half the language layer owns: the key it resolves and the stamps it puts on
        // the released value.
        declares("keyed_channels", "partition_pass");

        List<LocalOutput> released = release(PartitionPassRelay.class, "in", "input", List.of("a:1", "b:1", "a:2"));

        assertThat(payloads(released)).containsExactlyInAnyOrder("a:1", "b:1", "a:2");
        assertThat(payloadsOfKey(released, "a")).containsExactly("a:1", "a:2");
        assertThat(released)
                .as("each release carries the key its own value resolved to")
                .allSatisfy(output -> assertThat(output.metadata()).containsEntry("flz.key", key(payload(output))));
        assertThat(released)
                .as("the core stamps each keyed release with its Cell and its <changeSeq>.<emitOrdinal>")
                .allSatisfy(output -> {
                    assertThat(output.metadata().get("flz.cell"))
                            .endsWith("/partition_pass_tasklet/" + key(payload(output)));
                    assertThat(output.metadata().get("flz.seq")).matches("\\d+\\.0");
                });
    }

    @Test
    @DisplayName("dynamic_cell: the declared filter runs before keying and nothing else is lost")
    void theDeclaredFilterRunsBeforeKeyingAndNothingElseIsLost() {
        // The class's output is not keyed, so a released value carries no cell stamp and the per-key
        // minting is not visible on this channel. It is asserted above, on `partition_pass`, whose
        // tasklet is dynamic too and whose output IS keyed. What this class shows that that one does
        // not is the declared filter: a value it rejects answers no key, so the core is never asked
        // to mint a Cell for it.
        declares("dynamic_cell", "dynamic_cell");

        List<LocalOutput> released =
                release(DynamicCellRelay.class, "input", "kafka", List.of("a:1", "b:1", "drop", "a:2"));

        assertThat(payloads(released))
                .as("the filter dropped `drop` before it was keyed, and every other value reached its Cell")
                .containsExactlyInAnyOrder("a:1", "b:1", "a:2");
        assertThat(payloadsOfKey(released, "a")).containsExactly("a:1", "a:2");
        assertThat(released)
                .as("an unkeyed sink carries its channel and no identity stamps")
                .allSatisfy(output -> assertThat(output.channel()).isEqualTo("kafka"));
    }

    @Test
    @DisplayName("retry_ladder: a bounded mailbox that drops nothing loses no input")
    void aBoundedMailboxThatDropsNothingLosesNoInput() {
        // `poison_probe` is the manifest's other class for this capability and its poison path ends
        // in the dead-letter queue, which is the controller's. The ladder's other arm, a full
        // mailbox under `drop_nothing` where the producer is held rather than the value discarded,
        // is entirely the language layer's, and is what a run with no controller can see.
        declares("retry_ladder", "bounded_mailbox_drop_nothing");

        List<String> inputs = IntStream.range(0, 24).mapToObj(at -> "n" + at).toList();
        List<LocalOutput> released =
                release(BoundedMailboxDropNothingRelay.class, "drop_nothing_input", "drop_nothing_echo", inputs);

        assertThat(payloads(released))
                .as("the mailbox holds 4 and 24 were fed: under `drop_nothing` the feed waits, in order")
                .containsExactlyElementsOf(inputs);
    }

    @Test
    @DisplayName("cell_timers: a declared timer fires locally with its scheduled time")
    void aDeclaredTimerFiresLocallyWithItsScheduledTime() {
        declares("cell_timers", "session_timeout");

        List<LocalOutput> released = release(SessionTimeoutRelay.class, "input", "kafka", List.of("open"));

        assertThat(payloads(released))
                .as("the timer fired exactly once, carrying the timestamp it was scheduled for")
                .singleElement()
                .matches(closed -> closed.matches("closed@\\d+"));
    }

    @Test
    @DisplayName("a controller capability is refused by name, not approximated")
    void aControllerCapabilityIsRefusedByNameNotApproximated() {
        // The line local mode draws: the ten the controller owns stay refused. `tier_store` is
        // effectively-once, which needs a durable store to release from, so a local run says so
        // instead of running the graph.
        declares("effectively_once", "tier_store");

        FlezhaException refused =
                Refused.by("runtime.local_needs_controller", () -> LocalRelay.start(TierStoreRelay.class));
        assertThat(refused.details()).containsEntry("capability", "effectively_once");
    }

    @Test
    @DisplayName("parts: a local run drops every part and releases the output alone")
    void aLocalRunDropsEveryPart() {
        declares("parts", "part_echo");

        List<LocalOutput> released = release(PartEchoRelay.class, "in", "answer", List.of("3"));

        assertThat(payloads(released)).containsExactly("done:3");
    }

    @Test
    @DisplayName("parts: the core refuses a part whose channel ends in no output")
    void aPartWithNoOutputIsRefused() {
        PART_REFUSALS.clear();
        try (LocalRelay run = LocalRelay.start(PartToTaskletRelay.class)) {
            run.feed("input", "x");
            run.runUntilIdle(BUDGET);
        }

        assertThat(PART_REFUSALS).containsExactly("part.no_output");
    }

    /**
     * Run one class over {@code inputs} on {@code channel}, and return what {@code output} released.
     *
     * <p>Read after the run is closed, not at the moment it settles: the callback is invoked from the
     * run's own delivery thread, which can be behind the graph, and closing is what hands the last of
     * the released values over.
     */
    private static List<LocalOutput> release(Class<?> relay, String channel, String sink, List<String> inputs) {
        ConcurrentLinkedQueue<LocalOutput> released = new ConcurrentLinkedQueue<>();
        try (LocalRelay run = LocalRelay.start(relay)) {
            run.onOutput(sink, released::add);
            inputs.forEach(input -> run.feed(channel, input));
            run.runUntilIdle(BUDGET);
        }
        return List.copyOf(released);
    }

    /** The class under test is one the manifest names for the capability. */
    private static void declares(String capability, String factoryId) {
        JsonNode declared;
        try {
            // `lines()` drops comment lines and trims; JSON carries neither a comment nor a meaning
            // for the whitespace, so the joined lines are the file.
            declared = new ObjectMapper()
                    .readTree(String.join("\n", RepositoryFixture.testAppLines("conformance.json")))
                    .path("fixtures")
                    .path(capability);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> ids = new ArrayList<>();
        declared.forEach(id -> ids.add(id.asText()));
        assertThat(ids)
                .as("the local-mode run must drive a class the e2e drives for %s", capability)
                .contains(factoryId);
    }

    private static List<String> payloads(List<LocalOutput> released) {
        return released.stream().map(LocalModeCapabilitiesTest::payload).toList();
    }

    /** What one key released, in release order: the order a single Cell does promise. */
    private static List<String> payloadsOfKey(List<LocalOutput> released, String key) {
        return payloads(released).stream()
                .filter(value -> key(value).equals(key))
                .toList();
    }

    private static String payload(LocalOutput output) {
        return new String(output.payload(), StandardCharsets.UTF_8);
    }

    /** The key every keyed fixture here resolves: the text before the first {@code :}. */
    private static String key(String input) {
        int at = input.indexOf(':');
        return at < 0 ? input : input.substring(0, at);
    }

    /** Releases the input's bytes on the relay's output. */
    abstract static class EchoTasklet implements Tasklet<String, Void, byte[]> {
        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The test app's {@code SugarDoNothing} short form. */
    @com.flezha.sdk.authoring.ShortRelay(factoryId = "sugar_do_nothing", output = "sugar_do_nothing_echo")
    public static final class SugarDoNothing extends EchoTasklet {}

    /** Releases the input under the key it resolves to, on the class's keyed output. */
    @Tasklet.Spec(name = "partition_pass_tasklet")
    public static final class PartitionPassTasklet implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emitKeyed(key(input), input);
        }
    }

    public static final class PartitionPassRelay implements Relay {
        @Override
        public String factoryId() {
            return "partition_pass";
        }

        @Override
        public void channels(ChannelBuilder c) {
            // The entry channel is `in` and the output `input` because a forwarded frame keeps its
            // producing channel's name, and that is what selects the receiving relay's channel.
            c.input(String.class, "in")
                    .keyBy(LocalModeCapabilitiesTest::key)
                    .into(PartitionPassTasklet.class)
                    .keyed()
                    .output("input");
        }
    }

    @Tasklet.Spec(name = "dynamic_cell_tasklet")
    public static final class DynamicCellTasklet extends EchoTasklet {}

    public static final class DynamicCellRelay implements Relay {
        @Override
        public String factoryId() {
            return "dynamic_cell";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NOTHING)
                    .filter(input -> !input.equals("drop"))
                    .keyBy(LocalModeCapabilitiesTest::key)
                    .into(DynamicCellTasklet.class)
                    .output("kafka");
        }
    }

    @Tasklet.Spec(name = "drop_nothing_test_tasklet", mailboxCapacity = 4)
    public static final class DropNothingTasklet extends EchoTasklet {}

    public static final class BoundedMailboxDropNothingRelay implements Relay {
        @Override
        public String factoryId() {
            return "bounded_mailbox_drop_nothing";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "drop_nothing_input")
                    .onFull(OverflowPolicy.DROP_NOTHING)
                    .into(DropNothingTasklet.class)
                    .output("drop_nothing_echo");
        }
    }

    /**
     * A processing-time timer, because the answer to "nothing has arrived" cannot be an event-time
     * one. The test app reads the deadline from {@code TIMER_TEST_TIMEOUT_MS} so its e2e can shorten
     * it; a class declared here names the short one outright.
     */
    @Tasklet.Spec(name = "session_timeout_tasklet")
    public static final class SessionTimeoutTasklet implements Tasklet<String, Void, byte[]> {
        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.timerAfter(SESSION_TIMEOUT);
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.emit(("closed@" + firedAt).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class SessionTimeoutRelay implements Relay {
        @Override
        public String factoryId() {
            return "session_timeout";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(SessionTimeoutTasklet.class).output("kafka");
        }
    }

    /** Parts {@code 1..n}, then {@code done:<n>}; the test app's twin also fails a {@code retry:} input once. */
    @Tasklet.Spec(name = "part_echo")
    public static final class PartEchoTasklet implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            for (int part = 1; part <= Integer.parseInt(input); part++) {
                ctx.part(Integer.toString(part));
            }
            ctx.emit("done:" + input);
        }
    }

    public static final class PartEchoRelay implements Relay {
        @Override
        public String factoryId() {
            return "part_echo";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .retryBackoffMs(25)
                    .into(PartEchoTasklet.class)
                    .output("answer");
        }
    }

    /** The codes the core refused a part with. */
    static final ConcurrentLinkedQueue<String> PART_REFUSALS = new ConcurrentLinkedQueue<>();

    @Tasklet.Spec(name = "refused_part")
    public static final class RefusedPartTasklet implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            try {
                ctx.part(input);
            } catch (FlezhaException refused) {
                PART_REFUSALS.add(refused.code());
            }
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "part_drain")
    public static final class PartDrainTasklet implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class PartToTaskletRelay implements Relay {
        @Override
        public String factoryId() {
            return "part_to_tasklet";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(RefusedPartTasklet.class).into(PartDrainTasklet.class);
        }
    }

    /**
     * The tier IS durable state, so a class whose every tasklet declares {@code Void} state is
     * refused at registration before the local run reaches the capability. This one journals a
     * change so the refusal under test is the one local mode owns.
     */
    @Tasklet.Spec(name = "tier_store_tasklet")
    public static final class TierStoreTasklet implements Tasklet<String, TierStoreTasklet.Stored, String> {

        public static final class Stored {
            public String value = "";
        }

        public record Store(String value) implements Change<Stored> {
            @Override
            public void applyTo(Stored state) {
                state.value = value;
            }
        }

        @Override
        public void run(String input, Ctx<Stored, String> ctx) throws TaskletException {
            ctx.change(new Store(input));
            ctx.emitKeyed(key(input), input);
        }
    }

    public static final class TierStoreRelay implements Relay {
        @Override
        public String factoryId() {
            return "tier_store";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce());
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NOTHING)
                    .into(TierStoreTasklet.class)
                    .keyed()
                    .output("sink");
        }
    }
}
