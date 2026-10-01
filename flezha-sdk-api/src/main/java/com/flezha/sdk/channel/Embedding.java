package com.flezha.sdk.channel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How an edge embeds an operator class ({@link EdgeStream#into(Class, Class, Embedding)}): the
 * entry channel it feeds when the class declares several, the node's name (the class's factory id
 * by default), and the most stepped instances a keyed edge into a steps class keeps at once.
 *
 * <pre>{@code
 * requests.keyBy(Turn::user).into(Session.class, String.class, Embedding.of().maxCells(10_000));
 * }</pre>
 */
public record Embedding(@Nullable String input, @Nullable String name, @Nullable Integer maxCells) {

    /** No option written: the class's one entry channel, its factory id, no cap. */
    @Nonnull
    public static Embedding of() {
        return new Embedding(null, null, null);
    }

    /** The class's entry channel the edge feeds. */
    @Nonnull
    public Embedding input(@Nonnull String channel) {
        return new Embedding(channel, name, maxCells);
    }

    /** The node's name, for one class embedded at several graph positions. */
    @Nonnull
    public Embedding name(@Nonnull String node) {
        return new Embedding(input, node, maxCells);
    }

    /** The most stepped instances a keyed edge keeps at once. */
    @Nonnull
    public Embedding maxCells(int cap) {
        return new Embedding(input, name, cap);
    }
}
