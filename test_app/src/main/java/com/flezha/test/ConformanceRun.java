package com.flezha.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.FlezhaRunner;
import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EdgeStream;
import com.flezha.sdk.channel.OutputChain;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.core.DerivedDeclaration;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.relay.ChannelDescriptor;
import com.flezha.sdk.runtime.relay.RelayTopology;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder;
import com.flezha.sdk.tasklet.Tasklet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The conformance run: every row of {@code test/fixtures/reference_topologies.txt}
 * driven through THIS SDK's builder.
 *
 * <p>{@code flz_core/src/host/check_tests.rs} is the same table driven against
 * the record directly, because the core has no builder. This one maps the same
 * step vocabulary onto the Java graph builder, so what is measured here is what
 * an author of a Java relay actually writes.
 *
 * <p>Two entry points, both off {@link Main}:
 *
 * <ul>
 *   <li>{@code FLEZHA_CONFORMANCE_REPORT=<path>} (or
 *       {@code --conformance-report <path>}) drives every row and writes the
 *       report the drill reads ({@code test/system/topology_refusal_e2e_test.exs}),
 *       then exits without connecting to a controller. The drill names the path
 *       in the environment, which reaches the app whatever shell a manifest's
 *       launch line goes through.</li>
 *   <li>{@code FLEZHA_REFUSAL_FIXTURE=<topology id>} registers ONLY that row's
 *       topology at startup, so a refused one kills the process with the
 *       refusal's own name in its output.</li>
 * </ul>
 *
 * <p>The palette tasklets below exist only for this run: nothing registers
 * them but a row that names them, and they are in no capability's fixture set.
 *
 * <p>What this builder cannot author, and why the report skips it: the
 * producer-side links ({@code named}, {@code sink}, {@code keyed},
 * {@code branch}) live on {@link Stream} and an entry channel's handle is an
 * {@link EdgeStream}, so a row applying one to an entry channel has no Java
 * spelling; and a settings tree with a setting under the wrong parent is a
 * type error here, proven by the compile-fail fixtures under
 * {@code flezha-sdk-api/src/test/resources/compile_fail/} instead
 * ({@link #COMPILE_FAIL}). The fixture's {@code needs} column names both.
 */
public final class ConformanceRun {

    /** The fixture, relative to the repository root the app is launched from. */
    private static final String FIXTURE = "test/fixtures/reference_topologies.txt";

    /**
     * The authoring surfaces this builder carries — the same list this app's
     * {@code conformance.json} declares. A row needing anything else is skipped
     * BY NAME, which is what makes the skip checkable.
     */
    private static final List<String> SURFACES =
            List.of("merge", "branch", "steps", "shorthand", "shorthand_typed_output", "call");

    /** Where the compile-fail fixtures live, relative to the repository root. */
    private static final String COMPILE_FAIL = "sdk/java/flezha-sdk-api/src/test/resources/compile_fail/";

    /** The capacity a row declared per role, read as the tasklet is minted. */
    private static final Map<String, Integer> MAILBOX = new HashMap<>();

    /** The row a {@link RowRelay} declares when the runner defines it. */
    private static Row current;

    private ConformanceRun() {}

    /** A row of the four-column refusal table. */
    private record Row(String topology, String steps, String needs) {

        /** The JSON a {@code class.<setting>(<tree>)} step writes, or {@code null} when none does. */
        String classSetting(String setting) {
            for (String step : steps.split(";")) {
                String trimmed = step.trim();
                if (trimmed.startsWith("class." + setting + "(")) {
                    return trimmed.substring(trimmed.indexOf('(') + 1, trimmed.lastIndexOf(')'));
                }
            }
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // The fixture.

    private static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(fixturePath())) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] cols = trimmed.split("\\|");
            if (cols.length == 4) {
                rows.add(new Row(cols[1].trim(), cols[2].trim(), cols[3].trim()));
            }
        }
        return rows;
    }

    /**
     * The fixture as seen from wherever the app was launched: the drill runs it
     * from the repository root, a developer runs it from the app's own
     * directory.
     */
    private static Path fixturePath() throws IOException {
        Path base = Paths.get(".");
        for (int up = 0; up < 5; up++) {
            Path candidate = base.resolve(FIXTURE);
            if (Files.exists(candidate)) {
                return candidate;
            }
            base = base.resolve("..");
        }
        throw new IOException(FIXTURE + " is not under any parent of the working directory");
    }

    /** {@code verb(arg)} → {@code [verb, arg]}; a bare word → {@code [word, ""]}. */
    private static String[] splitCall(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            return new String[] {text.trim(), ""};
        }
        return new String[] {
            text.substring(0, open).trim(), text.substring(open + 1).replace(")", "").trim()
        };
    }

    // -----------------------------------------------------------------------
    // The interpreter.

    /** The class a row declares: the row's graph under the settings tree its class steps wrote. */
    public static final class RowRelay implements Relay {

        @Override
        public String factoryId() {
            return current.topology();
        }

        @Override
        public Run run() {
            String tree = current.classSetting("run");
            return tree == null ? Run.flow() : SettingsTreeJson.run(tree);
        }

        @Override
        public Durable durable() {
            String tree = current.classSetting("durable");
            return tree == null ? Durable.local() : SettingsTreeJson.durable(tree);
        }

        @Override
        public void channels(ChannelBuilder c) {
            interpret(c, current.steps());
        }
    }

    private static void interpret(ChannelBuilder c, String steps) {
        Map<String, EdgeStream<String>> handles = new LinkedHashMap<>();
        for (String step : steps.split(";")) {
            String trimmed = step.trim();
            if (!trimmed.isEmpty()) {
                step(c, handles, trimmed);
            }
        }
    }

    private static void step(ChannelBuilder c, Map<String, EdgeStream<String>> handles, String step) {
        String[] call = splitCall(step);
        switch (call[0]) {
            case "entry" -> handles.put(call[1], c.input(String.class, call[1]));
            case "merge" -> merge(c, handles, call[1]);
            case "branch" -> branch(handles, call[1]);
            default -> dotted(c, handles, step);
        }
    }

    /** {@code <handle>.<link>…} — a chain, a channel knob, or a class setting. */
    private static void dotted(ChannelBuilder c, Map<String, EdgeStream<String>> handles, String step) {
        int dot = step.indexOf('.');
        String head = step.substring(0, dot);
        String rest = step.substring(dot + 1);
        if (head.equals("class")) {
            // Read by RowRelay's run() and durable().
            return;
        }
        List<String> links = new ArrayList<>(List.of(rest.split("\\.")));
        String last = links.remove(links.size() - 1);
        EdgeStream<String> s = handle(handles, head);
        for (String link : links) {
            s = reshape(s, link);
        }
        // A chain whose tip reshapes is one nothing closes; it reaches the
        // record as written and the core answers it.
        if (isReshape(last)) {
            reshape(s, last);
            return;
        }
        if (last.startsWith("call")) {
            call(s, head, splitCall(last));
            return;
        }
        terminate(handles, s, last);
    }

    /**
     * {@code <x>.call…(<arg>)}: a 500 ms call whose Response channel feeds
     * the role that produces {@code x}, unless the step leaves it unread.
     */
    private static void call(EdgeStream<String> s, String role, String[] call) {
        if (!(s instanceof OutputChain<String> chain)) {
            throw new IllegalStateException(
                    "`call` is a producer-side link on an entry channel, which this SDK's builder does not carry");
        }
        CallTarget target = switch (call[0]) {
            case "call", "call_unread" -> CallTarget.id(call[1]);
            case "call_factory" -> CallTarget.factory(call[1]);
            case "call_factory_nocreate" -> CallTarget.factory(call[1]).create(false);
            case "call_deadline" -> CallTarget.id("pricing");
            default -> throw new IllegalStateException(
                    "the fixture spells a link this interpreter does not map: " + call[0]);
        };
        long deadlineMs = call[0].equals("call_deadline") ? Long.parseLong(call[1]) : 500;
        var response = chain.call(target, String.class, deadlineMs);
        if (!call[0].equals("call_unread")) {
            wire(response.map(String.class, RelayCallRelays::render), role);
        }
    }

    private static EdgeStream<String> handle(Map<String, EdgeStream<String>> handles, String name) {
        EdgeStream<String> s = handles.get(name);
        if (s == null) {
            throw new IllegalStateException("the row addresses `" + name + "`, which nothing has declared");
        }
        return s;
    }

    private static boolean isReshape(String link) {
        String verb = splitCall(link)[0];
        return verb.equals("map") || verb.equals("filter") || verb.equals("flat_map") || verb.equals("key");
    }

    /** The transform and key links declared before the terminator. */
    private static EdgeStream<String> reshape(EdgeStream<String> s, String link) {
        return switch (splitCall(link)[0]) {
            case "map" -> s.map(String.class, value -> value);
            case "filter" -> s.filter(value -> true);
            case "flat_map" -> s.flatMap(String.class, value -> List.of(value));
            case "key" -> s.keyBy(value -> value);
            default -> throw new IllegalStateException(
                    "the fixture spells a link this interpreter does not map: " + link);
        };
    }

    /** The link that closes a chain: a consumer, a channel knob, or a terminator. */
    private static void terminate(Map<String, EdgeStream<String>> handles, EdgeStream<String> s, String link) {
        String[] call = splitCall(link);
        switch (call[0]) {
            case "into" -> handles.put(call[1], wire(s, call[1]));
            case "retry_max" -> s.retryMax(Integer.parseInt(call[1]));
            case "on_full" -> s.onFull(overflow(call[1]));
            // Read by taskletClass() as the tasklet was minted.
            case "mailbox" -> {}
            default -> producerLink(s, call);
        }
    }

    /** The producer-side links, which only a tasklet's output channel carries. */
    private static void producerLink(EdgeStream<String> s, String[] call) {
        if (!(s instanceof Stream<String> producer)) {
            throw new IllegalStateException("`" + call[0]
                    + "` is a producer-side link on an entry channel, which this SDK's builder does not carry");
        }
        switch (call[0]) {
            case "named" -> producer.named(call[1]);
            case "sink" -> {
                if (call[1].isEmpty()) {
                    producer.sink();
                } else {
                    producer.sink(call[1]);
                }
            }
            case "keyed" -> producer.keyed();
            default -> throw new IllegalStateException(
                    "the fixture spells a link this interpreter does not map: " + call[0]);
        }
    }

    /** {@code merge(<task>, <x>, <x>…)} — one merge over the listed sources. */
    private static void merge(ChannelBuilder c, Map<String, EdgeStream<String>> handles, String arg) {
        String[] parts = arg.split(",");
        var merge = c.merge(String.class);
        for (int i = 1; i < parts.length; i++) {
            merge = merge.from(handle(handles, parts[i].trim()), value -> value);
        }
        handles.put(parts[0].trim(), merge.into(taskletClass(parts[0].trim())));
    }

    /**
     * {@code branch(<x>, <n>)} — the branch sugar's arity. The arms are empty:
     * what the row states is how many there are.
     */
    @SuppressWarnings("unchecked")
    private static void branch(Map<String, EdgeStream<String>> handles, String arg) {
        String[] parts = arg.split(",");
        if (!(handle(handles, parts[0].trim()) instanceof Stream<String> producer)) {
            throw new IllegalStateException(
                    "this SDK's branch sugar is a producer-side link; `" + parts[0].trim() + "` is an entry channel");
        }
        int arity = Integer.parseInt(parts[1].trim());
        Consumer<Stream<String>>[] arms = new Consumer[arity];
        for (int arm = 0; arm < arity; arm++) {
            arms[arm] = ignored -> {};
        }
        producer.branch(arms);
    }

    private static OverflowPolicy overflow(String policy) {
        return switch (policy) {
            case "drop_nothing" -> OverflowPolicy.DROP_NOTHING;
            case "drop_newest" -> OverflowPolicy.DROP_NEWEST;
            case "drop_oldest" -> OverflowPolicy.DROP_OLDEST;
            default -> throw new IllegalStateException(
                    "the fixture spells an overflow policy this interpreter does not map: " + policy);
        };
    }

    /** {@code <x>.into(<role>)} — the palette role bound to its fixture tasklet. */
    private static Stream<String> wire(EdgeStream<String> s, String role) {
        return s.into(taskletClass(role));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Tasklet<String, ?, String>> taskletClass(String role) {
        Class<?> bound = switch (role) {
            case "plain" -> switch (mailboxOf(role)) {
                case Palette.OVERSIZED_MAILBOX -> Palette.PlainOversized.class;
                case 0 -> Palette.PlainWithoutMailbox.class;
                default -> Palette.Plain.class;
            };
            case "second" -> Palette.Second.class;
            case "twin" -> Palette.Twin.class;
            case "static" -> Palette.StaticKeys.class;
            case "routed" -> Palette.Routed.class;
            case "dynamic" -> Palette.Dynamic.class;
            case "nochange" -> Palette.LogicalWithoutState.class;
            case "eventtime" -> Palette.EventTime.class;
            default -> throw new IllegalStateException("the fixture names a palette role that is not one: " + role);
        };
        return (Class<? extends Tasklet<String, ?, String>>) bound;
    }

    // -----------------------------------------------------------------------
    // Driving a row, and the report.

    /**
     * The capacities a row declares, read BEFORE the steps run: a tasklet is
     * minted by the {@code into} that first names it, and its capacity is read
     * then — one step earlier than the {@code mailbox} step that declares it.
     */
    private static void load(Row row) {
        current = row;
        MAILBOX.clear();
        for (String step : row.steps().split(";")) {
            int at = step.indexOf(".mailbox(");
            if (at >= 0) {
                MAILBOX.put(step.trim().substring(0, step.trim().indexOf(".mailbox(")), Integer.parseInt(
                        step.substring(at + ".mailbox(".length()).replace(")", "").trim()));
            }
        }
    }

    private static int mailboxOf(String role) {
        return MAILBOX.getOrDefault(role, Palette.MAILBOX);
    }

    /**
     * The two twins the fixture cites rather than re-spells: this app's own
     * fixtures, which is what the seven-column table states.
     */
    private static RelayTopology cited(String topology) {
        return switch (topology) {
            case "hybrid_channel" -> RelayTopologyBuilder.build(new HybridChannelRelay());
            case "commit_block_chain" -> RelayTopologyBuilder.build(new TierCommitBlockRelay());
            default -> throw new IllegalStateException(
                    "the fixture cites a topology this app does not declare: " + topology);
        };
    }

    private static RelayTopology topologyOf(Row row) {
        if (row.steps().equals("cited above")) {
            return cited(row.topology());
        }
        load(row);
        return RelayTopologyBuilder.build(new RowRelay());
    }

    /**
     * A settings tree with a setting under the wrong parent is a type error in this SDK: the row
     * is answered by the refusal it would earn elsewhere and the compile-fail fixture that pins it
     * here, whose text carries that name. {@code null} for every other row.
     */
    private static String[] typedRefusal(Row row) {
        if (!row.needs().equals("untyped_tree")) {
            return null;
        }
        String name = row.topology().equals("kind_literal") ? "topology.invalid_setting" : "topology.unknown_field";
        return new String[] {name, COMPILE_FAIL + row.topology() + ".java"};
    }

    /** One row's verdict, in the report's shape. */
    private static ObjectNode judge(ObjectMapper json, Row row) {
        ObjectNode entry = json.createObjectNode();
        entry.put("topology", row.topology());
        String[] typed = typedRefusal(row);
        if (typed != null) {
            entry.put("outcome", "cited");
            entry.putArray("names").add(typed[0]);
            entry.put("citation", typed[1]);
            return entry;
        }
        if (!row.needs().equals("-") && !SURFACES.contains(row.needs())) {
            entry.put("outcome", "skipped");
            entry.put("needs", row.needs());
            return entry;
        }
        List<Shorthand> shorthand = shorthandSteps(row);
        if (!shorthand.isEmpty()) {
            return judgeShorthand(json, entry, shorthand);
        }
        try {
            RelayTopology topology = topologyOf(row);
            entry.put("outcome", "accepted");
            entry.set("derived", derived(json, topology));
        } catch (FlezhaException refusal) {
            entry.put("outcome", "refused");
            ArrayNode names = entry.putArray("names");
            refusal.issueCodes().forEach(names::add);
        }
        return entry;
    }

    /**
     * A {@code shorthand(<id>|-, <sink>|-, <input>|-)} step. {@code -} is a field the row leaves
     * unwritten; {@code .noout} marks a body declaring no output, and {@code .topology} a class that
     * also implements the relay interface.
     */
    private record Shorthand(boolean hasSink, boolean noout, boolean topology) {

        /** The palette class this declaration spells: the topology body is judged before the output. */
        Class<?> declared() {
            if (topology) {
                return Palette.ShortFormWithTopology.class;
            }
            if (noout && hasSink) {
                return Palette.SinkWithoutOutput.class;
            }
            throw new IllegalStateException("a well-formed shorthand declaration earns no refusal of its own");
        }
    }

    /** The shorthand steps of a row, empty when it declares none. */
    private static List<Shorthand> shorthandSteps(Row row) {
        List<Shorthand> parsed = new ArrayList<>();
        for (String step : row.steps().split(";")) {
            Shorthand declaration = parseShorthand(step.trim());
            if (declaration != null) {
                parsed.add(declaration);
            }
        }
        return parsed;
    }

    private static Shorthand parseShorthand(String step) {
        boolean noout = step.endsWith(".noout");
        boolean topology = step.endsWith(".topology");
        String body = step.substring(0, step.lastIndexOf(')') + 1);
        String[] call = splitCall(body);
        if (!call[0].equals("shorthand")) {
            return null;
        }
        String[] fields = call[1].split(",");
        if (fields.length != 3) {
            throw new IllegalStateException("a shorthand step declares id, sink and input: read `" + step + "`");
        }
        return new Shorthand(!fields[1].trim().equals("-"), noout, topology);
    }

    /**
     * A short-form row's verdict, driven for real: registration reads the annotation, so a
     * malformed short form is refused there, and a second declaration under one factory id is
     * refused by the registry.
     */
    private static ObjectNode judgeShorthand(ObjectMapper json, ObjectNode entry, List<Shorthand> declarations) {
        try {
            drive(com.flezha.sdk.Flezha.createRunner("conformance_shorthand"), declarations);
            entry.put("outcome", "accepted");
        } catch (FlezhaException refusal) {
            entry.put("outcome", "refused");
            entry.putArray("names").add(refusal.code());
        }
        return entry;
    }

    /**
     * Register the row's short forms: the duplicate row's pair, where the second call is the one
     * under test, or the one malformed declaration.
     */
    private static void drive(FlezhaRunner runner, List<Shorthand> declarations) {
        if (declarations.size() > 1) {
            runner.registerFactory(ShorthandDupFlow.class);
            runner.registerFactory(ShorthandDupSteps.class);
        } else {
            runner.registerFactory(declarations.get(0).declared());
        }
    }

    /**
     * The derived declaration, in the columns the fixture's seven-column table
     * states: what the core answered, as the topology materialised it.
     */
    private static ArrayNode derived(ObjectMapper json, RelayTopology topology) {
        ArrayNode channels = json.createArrayNode();
        DerivedDeclaration answer = HostClassDeclaration.check(topology.declaration());
        for (int index = 0; index < topology.channels().size(); index++) {
            ChannelDescriptor channel = topology.channels().get(index);
            ObjectNode node = channels.addObject();
            node.put("index", index);
            node.put("name", channel.name());
            node.put("kind", kindName(channel));
            if (channel.producer().isPresent()) {
                node.put("producer", channel.producer().getAsInt());
            } else {
                node.putNull("producer");
            }
            ArrayNode consumers = node.putArray("consumers");
            channel.consumerIds().forEach(consumers::add);
            // The core's answer: a call end serializes like a sink but is not
            // one, so the descriptor alone cannot tell the two apart.
            node.put("sink", answer.channel(index).sink());
        }
        return channels;
    }

    /** The kind the core derived, spelled the way the record spells it. */
    private static String kindName(ChannelDescriptor channel) {
        return switch (channel.kind()) {
            case ENTRY -> "entry";
            case INTER_TASKLET -> "inter_task";
            case PLUGIN_BOUND -> "plugin";
        };
    }

    /**
     * Drive the whole table and write the report. Nothing here connects to a
     * controller: the run is the builder's answer to a file.
     */
    public static void writeReport(String path) throws IOException {
        ObjectMapper json = new ObjectMapper();
        ObjectNode report = json.createObjectNode();
        report.put("runtime", "java");
        ArrayNode judged = report.putArray("rows");
        List<Row> rows = rows();
        int skipped = 0;
        for (Row row : rows) {
            ObjectNode entry = judge(json, row);
            if ("skipped".equals(entry.get("outcome").asText())) {
                skipped++;
            }
            judged.add(entry);
        }
        Files.writeString(Path.of(path), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.err.println(
                "conformance: " + rows.size() + " rows, " + (rows.size() - skipped) + " ran, " + skipped + " skipped");
    }

    /**
     * Register ONLY the named row's topology, the way the app registers any
     * class. A refused row never reaches a connected runner: the process dies
     * here, naming the refusal.
     */
    public static void registerOnly(FlezhaRunner runner, String topology) throws IOException {
        Row row = rows().stream()
                .filter(candidate -> candidate.topology().equals(topology))
                .findFirst()
                .orElseThrow(() -> new IOException(FIXTURE + " has no row `" + topology + "`"));

        String[] typed = typedRefusal(row);
        if (typed != null) {
            System.err.println(topology + ": " + typed[0] + " is a compile error here; pinned by " + typed[1]);
            System.exit(1);
        }
        if (!row.needs().equals("-") && !SURFACES.contains(row.needs())) {
            System.err.println(topology + ": this SDK's builder carries no `" + row.needs()
                    + "`, so the row is skipped and nothing is registered");
            System.exit(1);
        }
        List<Shorthand> shorthand = shorthandSteps(row);
        if (!shorthand.isEmpty()) {
            try {
                drive(runner, shorthand);
            } catch (FlezhaException refusal) {
                System.err.println(topology + ": " + refusal.getMessage());
                System.exit(1);
            }
            System.err.println(topology + ": the runner accepted a short form the table refuses");
            System.exit(1);
        }
        if (row.steps().equals("cited above")) {
            cited(row.topology());
            return;
        }
        load(row);
        try {
            runner.registerFactory(RowRelay.class);
        } catch (FlezhaException refusal) {
            System.err.println(
                    topology + ": " + refusal.getMessage() + "; issues: " + String.join(", ", refusal.issueCodes()));
            System.exit(1);
        }
    }
}
