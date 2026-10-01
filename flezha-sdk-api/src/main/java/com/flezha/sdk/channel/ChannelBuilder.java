package com.flezha.sdk.channel;

import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Producer-first fluent graph builder consumed by
 * {@link com.flezha.sdk.operator.Operator#channels(ChannelBuilder)}, the
 * graph body of every operator.
 *
 * <p>{@code c.input(T.class, "name").into(A.class).into(B.class).sink("plugin")}
 * — {@code .into} wires a consumer and returns that tasklet's <b>output
 * stream handle</b> ({@link Stream}), so a linear pipeline is one chained
 * expression. Branching is handle reuse (hold the handle in a variable and
 * wire it twice) or the equivalent {@link Stream#branch} sugar. Fan-in is two
 * streams naming the same consumer class.
 *
 * <p>Entry channel names stay explicit and mandatory — they are the
 * operator's external input address. Inter-tasklet channel names
 * auto-derive from the producer node's identity, with {@link Stream#named}
 * as the override. Declarations land in a per-operator topology the runtime
 * freezes at registration.
 */
public interface ChannelBuilder {

    /**
     * Declare the entry channel fed by the controller. Any payload type is
     * accepted: {@code byte[]} is delivered unchanged and {@code String}
     * UTF-8-decoded, byte-identical to the pre-typed-entry contract; any
     * other type is deserialized at the entry edge from the wire bytes with
     * the SDK's default JSON codec (Jackson). A failing parse is classified
     * like a failing invocation and rides this channel's retry→DLQ ladder.
     * For a non-JSON wire format, use
     * {@link #input(Class, String, Function)}.
     */
    @Nonnull
    <T> EntryChannel<T> input(Class<T> type, String name);

    /**
     * Declare the entry channel with a custom {@code bytes → T} parser,
     * replacing the default JSON codec of {@link #input(Class, String)}.
     *
     * <p>The parser MUST be deterministic — the same bytes always produce the
     * same value, with no ambient reads (time, randomness, I/O) — the same
     * contract output serializers carry. A thrown exception is classified
     * like a failing invocation and rides this channel's retry→DLQ ladder.
     */
    @Nonnull
    <T> EntryChannel<T> input(Class<T> type, String name, Function<byte[], T> parser);

    /**
     * Open a <b>merged</b> subscription: one tasklet whose {@code Input} is
     * the sum type {@code type}, fed by two or more source channels that each
     * supply a mapping into it. See {@link Merge}.
     */
    @Nonnull
    <I> Merge<I> merge(Class<I> type);

    /**
     * Turn an operator-wide document stream into per-key deliveries: every key
     * seen on {@code traffic} receives each distinct document exactly once, on
     * that key's own next traffic item, as a {@link RoutedDocument} the consumer
     * keys by.
     *
     * <pre>{@code
     * EntryChannel<Thresholds> thresholds = c.input(Thresholds.class, "thresholds");
     * EntryChannel<Trade> trades = c.input(Trade.class, "trades");
     * Stream<RoutedDocument<Thresholds>> routed =
     *         c.routeLatest(Thresholds.class, thresholds, trades, Trade::symbol);
     *
     * c.merge(DetectorInput.class)
     *     .from(trades, DetectorInput::ofTrade)
     *     .from(routed, DetectorInput::ofThresholds)
     *     .keyBy(DetectorInput::key)
     *     .into(DetectorTasklet.class);
     * }</pre>
     *
     * <p>Why this shape rather than a broadcast: a dynamically-keyed tasklet
     * cannot receive one — every key function is total, and inside a partitioned
     * member a computed key is admitted against that member's key-group range.
     * Delivering by fanning out over a <i>remembered</i> key set would emit keys
     * this member does not own (a rescale moved them, or one out-of-range item
     * was seen during a reconcile window), and every such delivery parks
     * against the partition guard. Delivering on the key's own traffic makes
     * over-delivery impossible by construction: the only keys emitted are keys
     * whose traffic just proved this member owns them.
     *
     * <p>Contract:
     *
     * <ul>
     *   <li>a document {@link Object#equals equal} to the retained one is
     *       <b>inert</b> — a re-poll after a reconnect re-delivers the same
     *       document and nothing downstream sees it;
     *   <li>a differing document replaces it and bumps the version, so every key
     *       is owed one delivery again;
     *   <li>a key never seen in traffic receives <b>nothing</b>;
     *   <li><b>latency is one tick per key</b>: a key learns of a new document
     *       on its next traffic item, not when the document arrives. That is the
     *       price of the ownership-safety construction above.
     * </ul>
     *
     * <p>The document is kept <b>opaque</b> — retained and compared, never
     * parsed — so parsing (and its failure mode) stays with the consumer exactly
     * as when the consumer read the document stream directly. Declare
     * {@code equals} on it accordingly: a document type without value equality
     * makes every arrival a new version.
     *
     * <p>The block expands to a merge into ONE SDK-packaged single-cell
     * tasklet whose {@code {document, version, delivered}} state rides the
     * standard change journal: authoring sugar over existing primitives, not a
     * runtime feature. The node is named {@code route_latest} like any tasklet
     * node, so a second router in one operator needs
     * {@link #routeLatest(Class, EdgeStream, EdgeStream, Function, String)} to
     * name it.
     *
     * @param documentType the document's runtime type — the routed payload's
     *     {@code Class} token, and the registration the retained document's
     *     journaled state needs
     */
    @Nonnull
    <D, T> Stream<RoutedDocument<D>> routeLatest(
            Class<D> documentType, EdgeStream<D> documents, EdgeStream<T> traffic, Function<T, String> keyFn);

    /**
     * {@link #routeLatest(Class, EdgeStream, EdgeStream, Function)} with an
     * explicit node identity, so one operator may carry several routers (the
     * node-identity rule makes unnamed mentions of one class one node).
     */
    @Nonnull
    <D, T> Stream<RoutedDocument<D>> routeLatest(
            Class<D> documentType,
            EdgeStream<D> documents,
            EdgeStream<T> traffic,
            Function<T, String> keyFn,
            String nodeName);
}
