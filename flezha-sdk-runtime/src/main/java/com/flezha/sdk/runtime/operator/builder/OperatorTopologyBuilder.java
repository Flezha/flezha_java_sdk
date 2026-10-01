package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.channel.EdgeStream;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.Merge;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.channel.RetryPolicy;
import com.flezha.sdk.channel.RoutedDocument;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.core.HostLink;
import com.flezha.sdk.runtime.operator.ConsumerEdge;
import com.flezha.sdk.runtime.operator.EdgeYield;
import com.flezha.sdk.runtime.operator.OperatorTopology;
import com.flezha.sdk.runtime.operator.ResponseEnvelope;
import com.flezha.sdk.runtime.operator.TaskletModel;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Implements the producer-first graph {@link ChannelBuilder} (and the
 * {@link EntryChannel} / {@link Stream} handles). Tracks declared channels
 * and node registrations, validates the graph at {@link #build(Operator)}
 * time, and produces a frozen {@link OperatorTopology}.
 *
 * <p>Mirror of the Rust SDK's {@code channel/builder.rs} +
 * {@code operator/builder.rs}:
 * <ul>
 *   <li>A node is identified by its Tasklet class unless an explicit node
 *       name is given at wiring time; two unnamed mentions of one class are
 *       the same node (fan-in), a named mention is its own node, and an
 *       identity claimed by two distinct nodes is a collision.</li>
 *   <li>A node's output channel is created lazily on first use of its stream
 *       handle and named by the node's identity; {@code .named(...)}
 *       overrides. Derived, overridden, and entry names share one namespace
 *       whose duplicate check runs at build time.</li>
 *   <li>Graph shape: one or more entry channels feeding the graph, every node
 *       reachable from some entry, no cycles. Fan-out and fan-in are both
 *       legal.</li>
 *   <li>Typed entries decode at the entry edge (passthrough {@code byte[]},
 *       UTF-8 {@code String}, Jackson JSON otherwise, or a custom parser); a
 *       failing parse on a parsed entry rides the channel's retry→DLQ
 *       ladder.</li>
 * </ul>
 *
 * <p>The builder JUDGES nothing about the graph: it records the links each
 * handle was given and submits that record to {@code flz_core}'s one check,
 * which answers the derived declaration or the verdict. What it still refuses
 * itself is only what erasure makes and no record could carry — a payload type
 * that does not reach its consumer's input, and what {@link TaskletModel} reads
 * off a tasklet class (a type argument left generic, a setting out of range, two
 * clocks, a state with no constructor, colliding type ids), plus a tasklet class
 * with no public no-arg constructor. After {@link #build(Operator)} the
 * topology is frozen: a retained handle used afterwards throws
 * {@link IllegalStateException} naming the operator.
 */
public final class OperatorTopologyBuilder implements ChannelBuilder {

    private final List<ChannelEntry> channels = new ArrayList<>();
    private final List<TaskletEntry> tasklets = new ArrayList<>();
    /**
     * Every chain declared on a handle, indexed by the id the stream handles
     * carry. Each link pushes a NEW entry superseding the previous one, so a
     * chain's tip is the only entry that names the whole sequence.
     */
    private final List<PendingEdge> pending = new ArrayList<>();

    private final Map<String, Integer> taskletNameToId = new LinkedHashMap<>();
    private final List<FlezhaException> errors = new ArrayList<>();
    /** Ties the sources of one merge together across the channels they sit on. */
    private int mergeGroups;
    /** The operator classes this graph embeds, and the flow classes being inlined. */
    final OperatorEmbedding embedding = new OperatorEmbedding(this);

    /** Set at build time: every mutating path refuses afterwards. */
    private boolean frozen;

    private String frozenFactoryId = "<unbuilt>";

    /**
     * Run the class's {@link Operator#channels(ChannelBuilder)} once, submit the
     * record it and the class's settings write to the core's check, and freeze
     * the result into an {@link OperatorTopology}.
     *
     * @throws FlezhaException if any validation rule fails.
     */
    @Nonnull
    public static OperatorTopology build(@Nonnull Operator operator) {
        OperatorTopologyBuilder b = new OperatorTopologyBuilder();
        operator.channels(b);
        return b.freeze(operator);
    }

    /**
     * {@link #build(Operator)} for an authoring lambda, under the default settings.
     *
     * @throws FlezhaException if any validation rule fails.
     */
    @Nonnull
    public static OperatorTopology build(@Nonnull String factoryId, @Nonnull Consumer<ChannelBuilder> authoring) {
        return build(new Operator() {
            @Override
            public String factoryId() {
                return factoryId;
            }

            @Override
            public void channels(ChannelBuilder c) {
                authoring.accept(c);
            }
        });
    }

    @Override
    @Nonnull
    public <T> EntryChannel<T> input(@Nonnull Class<T> type, @Nonnull String name) {
        checkMutable();
        if (embedding.inlining()) {
            return embedding.entry(type, name);
        }
        boolean parsed = type != byte[].class && type != String.class;
        return declareEntry(type, name, bytes -> PlainPayload.decode(bytes, type), parsed);
    }

    @Override
    @Nonnull
    public <T> EntryChannel<T> input(
            @Nonnull Class<T> type, @Nonnull String name, @Nonnull Function<byte[], T> parser) {
        checkMutable();
        if (embedding.inlining()) {
            return embedding.entry(type, name);
        }
        return declareEntry(type, name, parser::apply, true);
    }

    <T> EntryChannel<T> declareEntry(Class<T> type, String name, Function<byte[], Object> parser, boolean parsed) {
        int id = declareChannel(type, name, ChannelKind.ENTRY);
        ChannelEntry entry = channels.get(id);
        entry.entryParser = parser;
        entry.entryParsed = parsed;
        return new EntryChannelImpl<>(this, id, name, null, type);
    }

    @Override
    @Nonnull
    public <I> Merge<I> merge(@Nonnull Class<I> type) {
        checkMutable();
        return new MergeImpl<>(this, type);
    }

    @Override
    @Nonnull
    public <D, T> Stream<RoutedDocument<D>> routeLatest(
            @Nonnull Class<D> documentType,
            @Nonnull EdgeStream<D> documents,
            @Nonnull EdgeStream<T> traffic,
            @Nonnull Function<T, String> keyFn) {
        return wireRouteLatest(documentType, documents, traffic, keyFn, null);
    }

    @Override
    @Nonnull
    public <D, T> Stream<RoutedDocument<D>> routeLatest(
            @Nonnull Class<D> documentType,
            @Nonnull EdgeStream<D> documents,
            @Nonnull EdgeStream<T> traffic,
            @Nonnull Function<T, String> keyFn,
            @Nonnull String nodeName) {
        return wireRouteLatest(documentType, documents, traffic, keyFn, nodeName);
    }

    /**
     * The expansion both {@code routeLatest} spellings share: the key function
     * is an edge transform on the traffic stream (so the traffic payload is
     * replaced by its key and never reaches the router), and the two streams are
     * one merge into the packaged tasklet.
     */
    @SuppressWarnings("unchecked")
    private <D, T> Stream<RoutedDocument<D>> wireRouteLatest(
            Class<D> documentType,
            EdgeStream<D> documents,
            EdgeStream<T> traffic,
            Function<T, String> keyFn,
            String nodeName) {
        checkMutable();
        // Resolve the node before wiring — `registerNode` is by identity, so the
        // merge below returns this same node — and register the document's own
        // class with its state codec: the router retains the document in
        // journaled state but is generic over its type, so only the wiring can
        // name it. That is what the `documentType` token is for.
        registerRouterDocumentType(registerNode(LatestDocumentRouter.class, nodeName), documentType);
        EdgeStream<String> keys = traffic.map(String.class, keyFn);
        MergeImpl<LatestDocumentRouter.Input> merge = new MergeImpl<>(this, LatestDocumentRouter.Input.class);
        merge.from(documents, LatestDocumentRouter.Input::ofDocument);
        merge.from(keys, LatestDocumentRouter.Input::ofTrafficKey);
        return (Stream<RoutedDocument<D>>) (Stream<?>) merge.wire(LatestDocumentRouter.class, nodeName);
    }

    private void registerRouterDocumentType(int node, Class<?> documentType) {
        TaskletEntry entry = tasklets.get(node);
        if (entry.kryoTypes.contains(documentType)) {
            return;
        }
        List<Class<?>> types = new ArrayList<>(entry.kryoTypes);
        types.add(documentType);
        entry.kryoTypes = List.copyOf(types);
    }

    @Nonnull
    private OperatorTopology freeze(Operator operator) {
        String factoryId = operator.factoryId();
        // Freeze first: any retained handle used from here on throws naming
        // the operator, whatever build's own outcome.
        frozenFactoryId = factoryId;
        frozen = true;
        if (!errors.isEmpty()) {
            // The record's edges are meaningless once a payload type does not
            // reach its consumer, so the core is never asked about them.
            throw FlezhaException.ofIssues(errors);
        }
        // A superseded entry is an intermediate link the chain it grew into
        // already carries; only a tip names a whole declared sequence.
        for (PendingEdge chain : pending) {
            if (!chain.superseded) {
                channels.get(chain.channel).chains.add(chain);
            }
        }
        int[] capacities = new int[tasklets.size()];
        for (int i = 0; i < tasklets.size(); i++) {
            TaskletEntry tasklet = tasklets.get(i);
            capacities[i] = MailboxCapacityResolver.resolve(tasklet.model.mailboxCapacity());
        }
        markKeyedTargetsDynamic();

        Map<String, Object> record = HostClassDeclaration.forClass(factoryId, operator, channels, tasklets, capacities);
        return TopologyMaterializer.materialize(
                operator, channels, tasklets, capacities, record, HostClassDeclaration.check(record));
    }

    /**
     * Cell mode is read off the WIRING, and the record states it: a
     * tasklet that names its own keys is static, one a keyed chain closes
     * onto is dynamic. A tasklet declaring both is NOT re-stated as dynamic
     * — its keys are its declaration, and the contradiction with the keyed
     * edge is the core's to name. Reading the builder's own record back is not
     * a judgement — what an unkeyed sibling edge into one MEANS is the core's
     * answer.
     */
    private void markKeyedTargetsDynamic() {
        for (PendingEdge chain : pending) {
            if (chain.superseded || chain.key == null) {
                continue;
            }
            for (HostLink link : chain.links) {
                Integer target = link.target();
                if (target != null && tasklets.get(target).model.cellKeys().isEmpty()) {
                    tasklets.get(target).dynamic = true;
                }
            }
        }
    }

    // ---- Internals ----------------------------------------------------------

    void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("operator `" + frozenFactoryId
                    + "`: the topology is frozen; stream handles are only usable inside channels()");
        }
    }

    /** The staging entry for a declared channel, by id. */
    ChannelEntry channel(int channelId) {
        return channels.get(channelId);
    }

    /** The staging entry for a registered node, by id. */
    TaskletEntry tasklet(int taskletId) {
        return tasklets.get(taskletId);
    }

    /** How many nodes are registered so far: the id the next one takes. */
    int taskletCount() {
        return tasklets.size();
    }

    private int declareChannel(Class<?> payloadType, String name, ChannelKind kind) {
        int id = channels.size();
        ChannelEntry entry = new ChannelEntry();
        entry.name = name;
        entry.kind = kind;
        entry.payloadType = payloadType;
        // DROP_NOTHING for every kind: a channel drops only when the operator declared it.
        entry.overflowPolicy = OverflowPolicy.DROP_NOTHING;
        channels.add(entry);
        return id;
    }

    /**
     * Resolve or create the node for {@code taskletClass} under the
     * node-identity rule: two unnamed mentions of one class are the same
     * node; an explicit node name creates/refers to a distinct node whose
     * identity string replaces the tasklet's name. An identity claimed by a
     * <i>different</i> declaration is a collision.
     */
    int registerNode(Class<? extends Tasklet<?, ?, ?>> taskletClass, String nodeName) {
        TaskletModel model = TaskletModel.read(taskletClass, instantiate(taskletClass));
        String identity = embedding.prefixed(nodeName != null ? nodeName : model.name());
        Integer existing = resolveIdentity(identity, taskletClass, nodeName != null);
        if (existing != null) {
            return existing;
        }
        model.errors().forEach(this::refuse);
        TaskletEntry entry = new TaskletEntry();
        entry.model = model;
        entry.kryoTypes = model.kryoTypes();
        entry.name = identity;
        entry.named = nodeName != null;
        int id = tasklets.size();
        tasklets.add(entry);
        taskletNameToId.put(identity, id);
        return id;
    }

    /**
     * The node already registered under {@code identity}, or {@code null} for a
     * fresh one. A claim by a different class, or by the other of the
     * named/unnamed spellings, is the collision.
     */
    private Integer resolveIdentity(String identity, Class<? extends Tasklet<?, ?, ?>> taskletClass, boolean named) {
        Integer existing = taskletNameToId.get(identity);
        if (existing == null) {
            return null;
        }
        TaskletEntry existingEntry = tasklets.get(existing);
        // A claim by a different class, or by the other of the named/unnamed
        // spellings, is a SECOND node under one identity: it is recorded as
        // one, and the core answers topology.node_identity_collision off the record.
        boolean sameNode = existingEntry.model.taskletClass().equals(taskletClass) && existingEntry.named == named;
        return sameNode ? existing : null;
    }

    /**
     * One instance of the class, which proves it constructible and answers a
     * written {@code kryoTypes()}; {@code null}, with the refusal recorded, when
     * it cannot be built.
     */
    private Tasklet<?, ?, ?> instantiate(Class<? extends Tasklet<?, ?, ?>> taskletClass) {
        String reason;
        try {
            return RuntimeBridge.construct(taskletClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            reason = e.toString();
        }
        refuse(FlezhaException.of(
                "topology.class_not_constructible", Map.of("class", taskletClass.getName(), "reason", reason)));
        return null;
    }

    /** Record one refusal; a class mentioned twice reports it once. */
    void refuse(FlezhaException error) {
        boolean known = errors.stream()
                .anyMatch(e -> e.code().equals(error.code()) && e.details().equals(error.details()));
        if (!known) {
            errors.add(error);
        }
    }

    /**
     * Record {@code channelId}'s chain as closing onto a consumer node
     * (created/resolved by identity). Returns the consumer's node id so its
     * handle can continue the chain.
     */
    int wireConsumer(
            int channelId, Class<? extends Tasklet<?, ?, ?>> taskletClass, String nodeName, Integer pendingChain) {
        return wireConsumer(channelId, taskletClass, nodeName, null, pendingChain, null);
    }

    /**
     * A transform/key chain declared on a stream handle but not yet closed by
     * {@code into(...)}. Every link pushes a NEW entry composing the previous
     * one, which is what makes reusing a handle mid-chain branch cleanly
     * instead of aliasing. Mirror of the Rust SDK's {@code PendingEdge}.
     */
    public static final class PendingEdge {
        int channel;
        /** The payload type after this chain's transforms. */
        Class<?> payloadType;
        /**
         * Every transform declared so far, composed into ONE closure.
         * Composition is the whole of "fusion": the runtime applies one closure
         * per edge and never learns that a chain existed.
         */
        Function<Object, EdgeYield> transform;

        Function<Object, String> key;
        /** This chain's links, in the order the author declared them. */
        public List<HostLink> links = new ArrayList<>();
        /** Set when a later link built on this one, so only a tip is recorded. */
        boolean superseded;

        /** The transform this chain's edge applies, or {@code null}. */
        Function<Object, EdgeYield> transform() {
            return transform;
        }

        /** The key this chain's edge applies, or {@code null}. */
        Function<Object, String> key() {
            return key;
        }
    }

    /**
     * Extend the chain at {@code base} (or open one on {@code channel}) with
     * one more link, returning the new chain's id.
     */
    int extendPending(
            int channel,
            Integer base,
            Class<?> payloadType,
            Function<Object, EdgeYield> link,
            Function<Object, String> key) {
        Function<Object, EdgeYield> priorTransform = null;
        Function<Object, String> priorKey = null;
        List<HostLink> priorLinks = List.of();
        if (base != null) {
            PendingEdge prior = pending.get(base);
            prior.superseded = true;
            priorTransform = prior.transform;
            priorKey = prior.key;
            priorLinks = prior.links;
        }
        PendingEdge chain = new PendingEdge();
        chain.channel = channel;
        chain.payloadType = payloadType;
        chain.transform = compose(priorTransform, link);
        chain.key = key != null ? key : priorKey;
        chain.links = new ArrayList<>(priorLinks);
        chain.links.add(link != null ? HostLink.transform() : HostLink.key());
        pending.add(chain);
        return pending.size() - 1;
    }

    /**
     * Close {@code base} on a terminator link, or open the one-link chain a
     * bare handle declares, answering the chain. The chain is recorded either
     * way; what closing it MEANS is the core's answer.
     */
    PendingEdge closeChain(int channel, Integer base, HostLink terminator) {
        if (base != null) {
            pending.get(base).links.add(terminator);
            return pending.get(base);
        }
        PendingEdge chain = new PendingEdge();
        chain.channel = channel;
        chain.links.add(terminator);
        pending.add(chain);
        return chain;
    }

    /**
     * Close {@code base} on a call end and declare the Response channel its
     * outcomes enter on, named after the calling channel as it is named now.
     * The chain's closures are the end's, as an edge's are the edge's.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <Q, A> EntryChannel<Response<Q, A>> attachCall(
            int channelId, Integer base, CallTarget target, long deadlineMs, Class<Q> request, Class<A> answer) {
        ChannelEntry entry = channels.get(channelId);
        String response = entry.name + ".response";
        PendingEdge chain = closeChain(channelId, base, HostLink.call(target, deadlineMs, response));
        entry.callEnd = new ConsumerEdge(-1, Optional.ofNullable(chain.transform), Optional.ofNullable(chain.key));
        if (entry.pluginSinkSerializer == null) {
            entry.pluginSinkSerializer = PlainPayload::encode;
        }
        return (EntryChannel)
                declareEntry(Response.class, response, bytes -> ResponseEnvelope.decode(bytes, request, answer), true);
    }

    /** One merge's group id, which ties its sources across their channels. */
    int nextMergeGroup() {
        return mergeGroups++;
    }

    /**
     * Compose two edge transforms into one. A drop short-circuits; a fan-out
     * feeds each of its values through the next link and concatenates.
     */
    private static Function<Object, EdgeYield> compose(
            Function<Object, EdgeYield> first, Function<Object, EdgeYield> second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return value -> {
            EdgeYield yielded = first.apply(value);
            List<Object> values = yielded.values();
            if (values.isEmpty()) {
                return EdgeYield.DROP;
            }
            if (values.size() == 1) {
                return second.apply(values.get(0));
            }
            List<Object> out = new ArrayList<>(values.size());
            for (Object v : values) {
                out.addAll(second.apply(v).values());
            }
            return EdgeYield.many(out);
        };
    }

    /**
     * Record {@code channelId}'s chain as closing onto a consumer node. A merge
     * subscription passes the declared merged input type, the mapping that
     * lifts this channel's payload into it, and the group tying its sources
     * together; what Rust checks at the {@code .into::<Task>()} call site with
     * an {@code Input = I} bound is checked here instead — erasure precludes
     * the compile-time form, so the same defect is caught at registration.
     */
    int wireConsumer(
            int channelId,
            Class<? extends Tasklet<?, ?, ?>> taskletClass,
            String nodeName,
            Class<?> deliveredTypeOverride,
            Integer pendingChain,
            Integer mergeGroup) {
        int taskletId = registerNode(taskletClass, nodeName);
        TaskletEntry tasklet = tasklets.get(taskletId);
        ChannelEntry channel = channels.get(channelId);
        Class<?> chainType = pendingChain == null ? null : pending.get(pendingChain).payloadType;
        refuseTypeMismatch(channel, tasklet, deliveredTypeOverride, chainType);
        closeChain(
                channelId,
                pendingChain,
                mergeGroup == null ? HostLink.into(taskletId) : HostLink.merge(taskletId, mergeGroup));
        return taskletId;
    }

    /**
     * The delivered payload is what the edge's transforms produce, not what the
     * channel carries — a merge mapping and a {@code .map(...)} chain are the
     * same thing here, and both reshape what the consumer observes.
     */
    private void refuseTypeMismatch(
            ChannelEntry channel, TaskletEntry tasklet, Class<?> deliveredTypeOverride, Class<?> chainType) {
        if (tasklet.embeds != null) {
            // The embedded class's entry reads the value it is written in.
            return;
        }
        Class<?> declared = deliveredTypeOverride != null ? deliveredTypeOverride : chainType;
        Class<?> deliveredType = declared != null ? declared : channel.payloadType;
        // A placeholder's types are not the author's: its construction is already refused.
        boolean placeholderProducer = declared == null
                && channel.producer != null
                && !tasklets.get(channel.producer).model.typesResolved();
        if (deliveredType.equals(tasklet.model.inputType()) || !tasklet.model.typesResolved() || placeholderProducer) {
            return;
        }
        errors.add(FlezhaException.of(
                "topology.payload_type_mismatch",
                Map.of(
                        "channel",
                        channel.name,
                        "delivered",
                        describe(deliveredType),
                        "tasklet",
                        tasklet.name,
                        "input",
                        describe(tasklet.model.inputType()))));
    }

    /**
     * The node's output channel, created lazily on first use so a leaf node
     * never carries a phantom channel. Named by the node's identity until a
     * {@code .named(...)} override; payload type = the node's declared
     * output type.
     */
    int ensureOutputChannel(int taskletId) {
        TaskletEntry tasklet = tasklets.get(taskletId);
        if (tasklet.outputChannelId != null) {
            return tasklet.outputChannelId;
        }
        Class<?> payloadType = tasklet.embeddedOutput != null ? tasklet.embeddedOutput : tasklet.model.outputType();
        int id = declareChannel(payloadType, tasklet.name, ChannelKind.INTER_TASKLET);
        channels.get(id).producer = taskletId;
        tasklet.outputChannelId = id;
        return id;
    }

    static String describe(Class<?> c) {
        return c == byte[].class ? "byte[]" : c.getName();
    }

    // ---- Phantom builder records -------------------------------------------

    /** Mutable internal record for a channel declaration. */
    public static final class ChannelEntry {
        public String name;
        public ChannelKind kind;
        Class<?> payloadType;
        public Integer producer;
        /** Every chain declared on this channel's handles, in author order. */
        public final List<PendingEdge> chains = new ArrayList<>();

        Function<Object, byte[]> pluginSinkSerializer;
        /**
         * The plugin end is a {@code response()}. The core's answer names no
         * sink for it, yet its emits leave as the plugin output a sink's do.
         */
        boolean response;

        /** The plugin end is a {@code call(...)}: its chain's closures, applied before each request. */
        ConsumerEdge callEnd;

        public OverflowPolicy overflowPolicy;
        public int retryMaxAttempts = RetryPolicy.DEFAULT.maxAttempts();
        public long retryBackoffBaseMs = RetryPolicy.DEFAULT.backoffBaseMs();
        public boolean keyed;
        /** Entry channels only: wire bytes → typed payload. */
        Function<byte[], Object> entryParser;
        /**
         * Entry channels whose parser is a real one (typed JSON or a custom
         * {@code input(type, name, parser)}). A failing parse rides the
         * channel's retry→DLQ ladder; {@code byte[]}/{@code String}
         * passthrough entries keep the pre-change drop-and-log behaviour.
         */
        boolean entryParsed;
    }

    /** Record a {@code .retryMax(...)} declaration; the core refuses a zero budget. */
    void setRetryMax(int channelId, int maxAttempts) {
        channels.get(channelId).retryMaxAttempts = maxAttempts;
    }

    /** Mutable internal record for a node registration. */
    public static final class TaskletEntry {
        /**
         * Node identity: the tasklet's name, or the explicit node name given at
         * wiring time. Feeds everything the name feeds — state directories,
         * journal entries, the derived channel name, diagnostics.
         */
        public String name;

        /** Whether this node was created with an explicit node name. */
        boolean named;

        /** What the node's class declares. */
        public TaskletModel model;

        /**
         * Set when some chain closing onto this node carries a key function —
         * Cell mode is read off the WIRING, not off the tasklet, and the
         * record states it so the core can judge the sibling edges.
         */
        public boolean dynamic;

        /** The class's encoded types, plus any only the wiring knows. */
        List<Class<?>> kryoTypes;

        Integer outputChannelId;

        /** The factory id of the steps class this node runs one stepped instance of per edge key. */
        public String embeds;

        /** The embedded class's entry channel the edge feeds, when it declares several. */
        public String input;

        /** The most stepped instances a keyed edge into the embedded class keeps at once. */
        public Integer maxCells;

        /** The embedded class's exit output type, which the node's output channel carries. */
        Class<?> embeddedOutput;
    }
}
