package com.flezha.sdk.channel;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The relay a {@link OutputChain#call call} reaches: a named instance (or a partition group,
 * whose member the request's key chooses), or an instance of a factory.
 *
 * <pre>{@code
 * CallTarget.id("pricing")
 * CallTarget.factory("agent").create(false).channel("turn")
 * CallTarget.id("pricing").answerChannel("quotes")
 * }</pre>
 *
 * @param target the instance or partition group called, {@code null} for a factory call
 * @param factory the factory called, {@code null} for a call to a named instance
 * @param create whether a factory call starts the instance its key names when none holds it
 * @param channel the callee's entry channel the request enters on; its first when {@code null}
 * @param answerChannel the callee's channel whose output answers; its first output for the request
 *     when {@code null}
 */
public record CallTarget(
        @Nullable String target,
        @Nullable String factory,
        boolean create,
        @Nullable String channel,
        @Nullable String answerChannel) {

    /** The instance, or partition group, named {@code id}. */
    @Nonnull
    public static CallTarget id(@Nonnull String id) {
        return new CallTarget(Objects.requireNonNull(id, "id"), null, true, null, null);
    }

    /**
     * An instance of {@code factory}: after a {@code keyBy}, the one whose id is the request's key,
     * started with the request when no instance holds it; without one, a new steps instance per
     * call.
     */
    @Nonnull
    public static CallTarget factory(@Nonnull String factory) {
        return new CallTarget(null, Objects.requireNonNull(factory, "factory"), true, null, null);
    }

    /**
     * Whether a factory call starts the instance its key names when none holds it (the default);
     * {@code false} answers {@code relay.not_found} instead.
     */
    @Nonnull
    public CallTarget create(boolean create) {
        return new CallTarget(target, factory, create, channel, answerChannel);
    }

    /** The callee's entry channel the request enters on; its first when unset. */
    @Nonnull
    public CallTarget channel(@Nonnull String channel) {
        return new CallTarget(target, factory, create, Objects.requireNonNull(channel, "channel"), answerChannel);
    }

    /** The callee's channel whose output answers the call; its first output for the request when unset. */
    @Nonnull
    public CallTarget answerChannel(@Nonnull String answerChannel) {
        return new CallTarget(target, factory, create, channel, Objects.requireNonNull(answerChannel, "answerChannel"));
    }
}
