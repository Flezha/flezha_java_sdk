package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.LocalOperator;
import com.flezha.sdk.SinkOutput;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Local mode over the five capabilities the LANGUAGE LAYER owns, each driven through the fixture
 * class the conformance test app's {@code conformance.json} names for it, so what passes here and what
 * passes end to end are not two different graphs.
 *
 * <p>The other ten capabilities in the vocabulary are the controller's: persistence,
 * effectively-once, correction, partitioning, bounded operators, liveness, restarts. Local mode refuses them
 * by name rather than approximating them.
 *
 * <p>What each test can assert is what a run with no controller can see. A durable write reaches a
 * null sink, a parked payload has nowhere to park, and a partition member's key range arrives with
 * {@code AddOperator}, so the assertions here are the output side of each capability, which is the
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
 * pool and keeps one Cell on one worker at a time, so the order two keys reach the sink in is not
 * something the language layer promises.
 */
class LocalModeCapabilitiesTest {

    private static final Duration BUDGET = Duration.ofSeconds(20);

    /** Short enough to keep the run brief, long enough not to race the feed that arms it. */
    private static final Duration SESSION_TIMEOUT = Duration.ofMillis(200);

    @Test
    @DisplayName("shorthand: a short form is built and run with no controller")
    void aShortFormIsBuiltAndRunWithNoController() {
        declares("shorthand", "sugar_do_nothing");

        List<SinkOutput> released =
                release(SugarDoNothing.class, "input", "sugar_do_nothing_echo", List.of("alpha", "beta"));

        assertThat(payloads(released))
                .as("the builder produced a graph the core ran, in order")
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("keyed_channels: a keyed sink stamps the key the class resolved")
    void aKeyedSinkStampsTheKeyTheClassResolved() {
        // `partition_relay` is the manifest's only keyed-channel class. Partitioning itself is the
        // controller's (a member's key-group range arrives with `AddOperator`), so what is asserted
        // here is the half the language layer owns: the key it resolves and the stamps it puts on
        // the released value.
        declares("keyed_channels", "partition_relay");

        List<SinkOutput> released =
                release(PartitionRelayUnboundedOperator.class, "in", "input", List.of("a:1", "b:1", "a:2"));

        assertThat(payloads(released)).containsExactlyInAnyOrder("a:1", "b:1", "a:2");
        assertThat(payloadsOfKey(released, "a")).containsExactly("a:1", "a:2");
        assertThat(released)
                .as("each release carries the key its own value resolved to")
                .allSatisfy(output -> assertThat(output.metadata()).containsEntry("flz.key", key(payload(output))));
        assertThat(released.get(0).metadata()).containsKeys("flz.cell", "flz.seq");
    }

    @Test
    @DisplayName("dynamic_cell: the declared filter runs before keying and nothing else is lost")
    void theDeclaredFilterRunsBeforeKeyingAndNothingElseIsLost() {
        // The class's sink is not keyed, so a released value carries no cell stamp and the per-key
        // minting is not visible on this channel. It is asserted above, on `partition_relay`, whose
        // tasklet is dynamic too and whose sink IS keyed. What this class shows that that one does
        // not is the declared filter: a value it rejects answers no key, so the core is never asked
        // to mint a Cell for it.
        declares("dynamic_cell", "dynamic_cell");

        List<SinkOutput> released =
                release(DynamicCellUnboundedOperator.class, "input", "kafka", List.of("a:1", "b:1", "drop", "a:2"));

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
        List<SinkOutput> released = release(
                BoundedMailboxDropNothingUnboundedOperator.class, "drop_nothing_input", "drop_nothing_echo", inputs);

        assertThat(payloads(released))
                .as("the mailbox holds 4 and 24 were fed: under `drop_nothing` the feed waits, in order")
                .containsExactlyElementsOf(inputs);
    }

    @Test
    @DisplayName("cell_timers: a declared timer fires locally with its scheduled time")
    void aDeclaredTimerFiresLocallyWithItsScheduledTime() {
        declares("cell_timers", "session_timeout");

        List<SinkOutput> released = release(SessionTimeoutUnboundedOperator.class, "input", "kafka", List.of("open"));

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

        FlezhaException refused = Refused.by(
                "runtime.local_needs_controller", () -> LocalOperator.start(TierStoreUnboundedOperator.class));
        assertThat(refused.details()).containsEntry("capability", "effectively_once");
    }

    /**
     * Run one class over {@code inputs} on {@code channel}, and return what {@code sink} released.
     *
     * <p>Read after the run is closed, not at the moment it settles: the callback is invoked from the
     * run's own delivery thread, which can be behind the graph, and closing is what hands the last of
     * the released values over.
     */
    private static List<SinkOutput> release(Class<?> operator, String channel, String sink, List<String> inputs) {
        ConcurrentLinkedQueue<SinkOutput> released = new ConcurrentLinkedQueue<>();
        try (LocalOperator run = LocalOperator.start(operator)) {
            run.onSink(sink, released::add);
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

    private static List<String> payloads(List<SinkOutput> released) {
        return released.stream().map(LocalModeCapabilitiesTest::payload).toList();
    }

    /** What one key released, in release order: the order a single Cell does promise. */
    private static List<String> payloadsOfKey(List<SinkOutput> released, String key) {
        return payloads(released).stream()
                .filter(value -> key(value).equals(key))
                .toList();
    }

    private static String payload(SinkOutput output) {
        return new String(output.payload(), StandardCharsets.UTF_8);
    }

    /** The key every keyed fixture here resolves: the text before the first {@code :}. */
    private static String key(String input) {
        int at = input.indexOf(':');
        return at < 0 ? input : input.substring(0, at);
    }

    /** Releases the input's bytes on the operator's sink. */
    abstract static class EchoTasklet implements Tasklet<String, Void, byte[]> {
        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The test app's {@code SugarDoNothing} short form. */
    @com.flezha.sdk.authoring.UnboundedOperator(id = "sugar_do_nothing", sink = "sugar_do_nothing_echo")
    public static final class SugarDoNothing extends EchoTasklet {}

    /** Releases the input under the key it resolves to, on the class's keyed sink. */
    @Tasklet.Spec(name = "partition_relay_tasklet")
    public static final class PartitionRelayTasklet implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emitKeyed(key(input), input);
        }
    }

    public static final class PartitionRelayUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "partition_relay";
        }

        @Override
        public void channels(ChannelBuilder c) {
            // The entry channel is `in` and the sink `input` because a forwarded frame keeps its
            // producing channel's name, and that is what selects the receiving operator's channel.
            c.input(String.class, "in")
                    .keyBy(LocalModeCapabilitiesTest::key)
                    .into(PartitionRelayTasklet.class)
                    .keyed()
                    .sink("input");
        }
    }

    @Tasklet.Spec(name = "dynamic_cell_tasklet")
    public static final class DynamicCellTasklet extends EchoTasklet {}

    public static final class DynamicCellUnboundedOperator implements UnboundedOperator {
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
                    .sink("kafka");
        }
    }

    @Tasklet.Spec(name = "drop_nothing_test_tasklet", capacity = 4)
    public static final class DropNothingTasklet extends EchoTasklet {}

    public static final class BoundedMailboxDropNothingUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "bounded_mailbox_drop_nothing";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "drop_nothing_input")
                    .onFull(OverflowPolicy.DROP_NOTHING)
                    .into(DropNothingTasklet.class)
                    .sink("drop_nothing_echo");
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

    public static final class SessionTimeoutUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "session_timeout";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(SessionTimeoutTasklet.class).sink("kafka");
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

    public static final class TierStoreUnboundedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "tier_store";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.input(String.class, "input")
                    .onFull(OverflowPolicy.DROP_NOTHING)
                    .into(TierStoreTasklet.class)
                    .keyed()
                    .sink("sink");
        }
    }
}
