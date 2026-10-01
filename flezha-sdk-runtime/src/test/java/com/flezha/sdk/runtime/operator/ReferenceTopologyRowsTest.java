package com.flezha.sdk.runtime.operator;

import static com.flezha.sdk.runtime.operator.OperatorTopologyBuilderTest.catchConfigException;
import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OutputChain;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.RepositoryFixture;
import com.flezha.sdk.runtime.core.DerivedDeclaration;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rows of {@code test/fixtures/reference_topologies.txt}, authored through this SDK's builder.
 *
 * <p>That fixture is the cross-language contract: one row per refusal
 * {@code flz_core::host::register::check} names, and the channels a well-formed row derives to. The
 * whole table runs through the row interpreter in {@code test/apps/java_runner} under
 * {@code :interop}; these two cases keep the fixture inside the Java build itself, where a pull
 * request can see it.
 *
 * <p>The fixture states the verdict and this class states the authoring: each row is written as the
 * fluent calls its {@code steps} column spells, and the expected refusal name and the expected
 * derived lines are read out of the file, so a row it renames or drops fails here rather than
 * passing on a stale literal. {@code OperatorTopologyBuilderTest.CoreRefusals} is the other
 * half, pinning the SENTENCES for the shapes this SDK used to judge in its own words; its tasklets
 * and its verdict catcher are reused here rather than declared a second time.
 *
 * <p>What makes the refusal case a statement about the core rather than about a string: the
 * {@code FlezhaException}'s {@code details.issues} carries the code of every refusal the core's
 * verdict found, and nothing the builder still refuses itself carries a code the fixture gives a
 * row.
 */
class ReferenceTopologyRowsTest {

    private static final String FIXTURE = "test/fixtures/reference_topologies.txt";

    /**
     * One authored row: the fixture's topology id, the run its {@code class.run} step writes, and
     * what the rest of its {@code steps} column spells.
     */
    private record Row(String topology, Operator.Run run, Consumer<ChannelBuilder> authoring) {

        Row(String topology, Consumer<ChannelBuilder> authoring) {
            this(topology, Operator.Run.flow(), authoring);
        }

        OperatorTopology build() {
            return OperatorTopologyBuilderTest.build(topology, run, authoring);
        }
    }

    /**
     * The malformed rows authored here, chosen from the reference names no {@code sdk/java} test
     * asserted. The fixture's palette roles bind to tasklets the builder test already declares:
     * {@code plain} is {@code SingleTasklet}, {@code second} {@code GraphStage1}, {@code routed}
     * {@code StaticTasklet}, and {@code dynamic} {@code DynamicModeOnlyTasklet}, whose keyed
     * edge is what makes it dynamic.
     */
    private List<Row> malformed() {
        return List.of(
                // entry(in); in.key.key.into(plain)
                new Row("dup_key_by", c -> c.input(String.class, "in")
                        .keyBy(value -> value)
                        .keyBy(value -> value)
                        .into(OperatorTopologyBuilderTest.SingleTasklet.class)),
                // entry(in); in.key.map.into(plain)
                new Row("map_after_key", c -> c.input(String.class, "in")
                        .keyBy(value -> value)
                        .map(String.class, value -> value)
                        .into(OperatorTopologyBuilderTest.SingleTasklet.class)),
                // entry(in); in.into(plain); plain.sink(out); plain.map.into(second)
                new Row("map_after_sink", c -> {
                    var plain = c.input(String.class, "in").into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    plain.sink("out");
                    plain.map(String.class, value -> value).into(OperatorTopologyBuilderTest.GraphStage1.class);
                }),
                // entry(in); in.into(plain); in.map
                new Row("dangling_chain", c -> {
                    var in = c.input(String.class, "in");
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    in.map(String.class, value -> value);
                }),
                // entry(in); in.map.into(plain); in.into(plain)
                new Row("edge_rewired", c -> {
                    var in = c.input(String.class, "in");
                    in.map(String.class, value -> value).into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class);
                }),
                // entry(in); in.key.into(routed)
                new Row("key_on_static", c -> c.input(String.class, "in")
                        .keyBy(value -> value)
                        .into(OperatorTopologyBuilderTest.StaticTasklet.class)),
                // entry(in); entry(other); in.key.into(dynamic); other.into(dynamic)
                new Row("unkeyed_dynamic", c -> {
                    var in = c.input(String.class, "in");
                    var other = c.input(String.class, "other");
                    in.keyBy(value -> value).into(OperatorTopologyBuilderTest.DynamicModeOnlyTasklet.class);
                    other.into(OperatorTopologyBuilderTest.DynamicModeOnlyTasklet.class);
                }),
                // entry(in); in.into(plain); plain.named(a); plain.sink(b)
                new Row("named_twice", c -> c.input(String.class, "in")
                        .into(OperatorTopologyBuilderTest.SingleTasklet.class)
                        .named("a")
                        .sink("b")),
                // entry(in); in.into(plain); in.into(second); plain.sink(out); second.sink(out)
                new Row("two_channels_one_sink", c -> {
                    var in = c.input(String.class, "in");
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class).sink("out");
                    in.into(OperatorTopologyBuilderTest.GraphStage1.class).sink("out");
                }),
                // entry(in); in.into(plain); plain.sink(out); plain.response
                new Row("response_beside_sink", c -> {
                    var plain = c.input(String.class, "in").into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    plain.sink("out");
                    plain.response();
                }),
                // entry(in); in.into(plain); plain.named(answer); plain.response; plain.response
                new Row("two_responses", c -> {
                    var plain = c.input(String.class, "in").into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    plain.named("answer");
                    plain.response();
                    plain.response();
                }),
                // entry(in); in.into(plain); plain.sink(out); plain.call(pricing)
                new Row("call_beside_sink", c -> {
                    var plain = plain(c);
                    plain.sink("out");
                    callBack(plain, CallTarget.id("pricing"), 500);
                }),
                // entry(in); in.into(plain); plain.named(ask); plain.call_factory_nocreate(agent)
                new Row(
                        "call_nocreate_unkeyed",
                        c -> callBack(
                                plain(c).named("ask"),
                                CallTarget.factory("agent").create(false),
                                500)),
                // entry(in); in.into(plain); plain.named(ask); plain.call_deadline(0)
                new Row("call_deadline_zero", c -> callBack(plain(c).named("ask"), CallTarget.id("pricing"), 0)),
                // entry(in); in.into(plain); plain.named(ask); plain.call_unread(pricing)
                new Row("call_unread", c -> plain(c).named("ask").call(CallTarget.id("pricing"), String.class, 500)));
    }

    /** {@code entry(in); in.into(plain)}, the opening every call row shares. */
    private static Stream<String> plain(ChannelBuilder c) {
        return c.input(String.class, "in").into(OperatorTopologyBuilderTest.SingleTasklet.class);
    }

    /** A call whose Response channel is wired back into {@code plain}, the role that made it. */
    private static void callBack(OutputChain<String> chain, CallTarget target, long deadlineMs) {
        chain.call(target, String.class, deadlineMs)
                .map(String.class, Response::request)
                .into(OperatorTopologyBuilderTest.SingleTasklet.class);
    }

    /** The well-formed rows whose derived lines the fixture's seven-column table carries. */
    private List<Row> wellFormed() {
        return List.of(
                // entry(in); in.into(plain); in.into(plain)
                new Row("fan_in_noop", c -> {
                    var in = c.input(String.class, "in");
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class);
                }),
                // entry(in); in.into(plain); plain.named(renamed); plain.sink
                new Row("named_sink", c -> {
                    var plain = c.input(String.class, "in").into(OperatorTopologyBuilderTest.SingleTasklet.class);
                    plain.named("renamed");
                    plain.sink();
                }),
                // entry(in); in.into(plain); plain.sink(items_output)
                new Row("sink_names_channel", c -> c.input(String.class, "in")
                        .into(OperatorTopologyBuilderTest.SingleTasklet.class)
                        .sink("items_output")),
                // entry(in); in.into(plain); in.into(second); plain.named(accepted); plain.response;
                // second.named(refused); second.response
                new Row("two_response_ends", c -> {
                    var in = c.input(String.class, "in");
                    in.into(OperatorTopologyBuilderTest.SingleTasklet.class)
                            .named("accepted")
                            .response();
                    in.into(OperatorTopologyBuilderTest.GraphStage1.class)
                            .named("refused")
                            .response();
                }),
                // entry(in); in.into(plain); plain.named(ask); plain.call(pricing)
                new Row("call_back_into_caller", c -> callBack(plain(c).named("ask"), CallTarget.id("pricing"), 500)),
                // entry(in); in.into(plain); plain.named(ask); plain.key.call_factory(agent)
                new Row(
                        "call_factory_by_key",
                        c -> callBack(plain(c).named("ask").keyBy(value -> value), CallTarget.factory("agent"), 500)),
                // class.run({"flow":{"exactly":"effectively_once"}}); entry(in); in.into(plain);
                // plain.named(answer); plain.response
                new Row(
                        "eo_unkeyed_response",
                        new Operator.Run.Flow(new Operator.Exactly.EffectivelyOnce()),
                        c -> c.input(String.class, "in")
                                .into(OperatorTopologyBuilderTest.SingleTasklet.class)
                                .named("answer")
                                .response()));
    }

    @Test
    @DisplayName("A malformed row refuses with the reference name")
    void aMalformedRowRefusesWithTheReferenceName() throws Exception {
        for (Row row : malformed()) {
            String name = refusalOf(row.topology());
            FlezhaException refused = catchConfigException(row::build);

            assertThat(refused)
                    .as("%s is a refusal row of %s and this builder accepted it", row.topology(), FIXTURE)
                    .isNotNull();
            assertThat(refused.issueCodes())
                    .as(
                            "the codes the core listed in its verdict for %s. What the builder still"
                                    + " refuses itself carries no code the fixture gives a row, so a"
                                    + " reference code here can only be the core's",
                            row.topology())
                    .contains(name);
        }
    }

    @Test
    @DisplayName("A well-formed row derives to the fixture's bytes")
    void aWellFormedRowDerivesToTheFixturesBytes() throws Exception {
        for (Row row : wellFormed()) {
            OperatorTopology topology = row.build();
            // The record the builder submitted, answered by the same check it took its
            // verdict from: the derived declaration itself, not a second reading of it.
            DerivedDeclaration derived = HostClassDeclaration.check(topology.declaration());

            assertThat(columns(derived))
                    .as("%s derived a declaration %s does not pin", row.topology(), FIXTURE)
                    .isEqualTo(pinnedLines(row.topology()));
        }
    }

    @Test
    @DisplayName("A call from a single-pass steps class refuses with the reference name")
    void aCallFromASinglePassStepsClassRefuses() throws Exception {
        // class.run("steps"); entry(in); in.into(plain); plain.named(ask); plain.call(pricing)
        Row once = new Row(
                "call_once_steps",
                Operator.Run.steps(),
                c -> callBack(plain(c).named("ask"), CallTarget.id("pricing"), 500));

        FlezhaException refused = catchConfigException(once::build);

        assertThat(refused).isNotNull();
        assertThat(refused.issueCodes()).contains(refusalOf("call_once_steps"));
    }

    /** The refusal the fixture's four-column table names for a row. */
    private String refusalOf(String topology) throws IOException {
        for (List<String> row : RepositoryFixture.rows(FIXTURE)) {
            if (row.size() == 4 && row.get(1).equals(topology)) {
                assertThat(row.get(0))
                        .as("%s is a well-formed row, so it refuses with nothing", topology)
                        .isNotEqualTo("ok");
                return row.get(0);
            }
        }
        throw new AssertionError(FIXTURE + " carries no row `" + topology + "`");
    }

    /** The six columns the fixture pins for one topology's derived declaration. */
    private List<List<String>> pinnedLines(String topology) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        for (List<String> row : RepositoryFixture.rows(FIXTURE)) {
            if (row.size() == 7 && row.get(0).equals(topology)) {
                lines.add(row.subList(1, 7));
            }
        }
        if (lines.isEmpty()) {
            throw new AssertionError(FIXTURE + " pins no derived lines for `" + topology + "`");
        }
        return lines;
    }

    /** The core's answer in the fixture's columns: index, name, kind, producer, consumers, sink. */
    private List<List<String>> columns(DerivedDeclaration derived) {
        List<List<String>> lines = new ArrayList<>();
        for (DerivedDeclaration.Channel channel : derived.channels()) {
            lines.add(List.of(
                    String.valueOf(channel.index()),
                    channel.name(),
                    channel.kind(),
                    absent(channel.producer()),
                    channel.consumers().isEmpty()
                            ? "-"
                            : channel.consumers().stream()
                                    .map(edge -> String.valueOf(edge.tasklet()))
                                    .collect(Collectors.joining(",")),
                    absent(channel.sink())));
        }
        return lines;
    }

    /** The fixture spells an absent column `-`. */
    private String absent(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
