package com.flezha.sdk.channel;

import javax.annotation.Nonnull;

/**
 * The handle for a channel fed from outside the graph, returned by
 * {@link ChannelBuilder#input(Class, String)}. This is where a graph starts.
 *
 * <pre>{@code
 * c.input(String.class, "lines").into(SplitTasklet.class).output("words");
 * }</pre>
 *
 * <p>An entry channel is fed by the controller, so it has no producer side. Everything it offers is
 * the shared {@link EdgeStream} surface that {@link Stream} also carries, including the wiring
 * transforms:
 *
 * <pre>{@code
 * c.input(Trade.class, "trades")
 *         .filter(t -> t.qty() > 0)
 *         .keyBy(Trade::symbol)
 *         .into(CandleTasklet.class);
 * }</pre>
 *
 * <p>Sharing that surface is deliberate: it keeps the entry side from drifting away from
 * {@link Stream}, so a transform learned in one place works in the other.
 *
 * <p>Like {@link Stream}, this handle is usable only inside {@code channels()}. The graph freezes
 * at registration, and a retained handle used afterwards throws {@link IllegalStateException}
 * naming the relay.
 */
public interface EntryChannel<T> extends EdgeStream<T> {

    /**
     * Say what this channel is for, in one sentence. The relay's API announces it, so a person
     * reading {@code flz relay info} or an AI agent calling the relay as a tool sees it.
     */
    @Nonnull
    EntryChannel<T> describe(String description);

    /**
     * Declare the JSON Schema of this channel's input, overriding the one derived from a record
     * input type. It must be a JSON object with a {@code type}, on a JSON channel; anything else
     * fails registration with {@code topology.schema_invalid}.
     *
     * <pre>{@code
     * c.input(Order.class, "place")
     *         .describe("Places an order")
     *         .schema("{\"type\":\"object\",\"required\":[\"pair\"]}")
     *         .into(PlaceTasklet.class);
     * }</pre>
     */
    @Nonnull
    EntryChannel<T> schema(String json);
}
