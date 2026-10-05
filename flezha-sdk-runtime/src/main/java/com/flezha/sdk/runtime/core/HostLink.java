package com.flezha.sdk.runtime.core;

import com.flezha.sdk.channel.CallTarget;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One link of a declared chain, as the {@code HostClassDecl} record spells it.
 * A chain is one fluent sequence off a channel handle, in the order the author
 * called it; {@code into}, {@code merge}, {@code output},
 * {@code call} and {@code branch} close the chain they stand in.
 *
 * <p>Closures never enter the record — the builder keeps them beside it and
 * attaches them to the edge the core derived from the chain. What a chain
 * MEANS (its consumers, the channel's kind, its name, its output) is
 * {@code flz_core::host::register::check}'s answer, never a claim made here.
 */
public record HostLink(
        @Nonnull String link,
        @Nullable String name,
        @Nullable Integer tasklet,
        @Nullable Integer group,
        @Nullable Integer arms,
        @Nullable Map<String, Object> call) {

    /** One {@code map} / {@code filter} / {@code flatMap} link. */
    @Nonnull
    public static HostLink transform() {
        return new HostLink("transform", null, null, null, null, null);
    }

    /** One {@code keyBy} link. */
    @Nonnull
    public static HostLink key() {
        return new HostLink("key", null, null, null, null, null);
    }

    /** Overrides the channel's derived name for every terminator. */
    @Nonnull
    public static HostLink named(@Nonnull String name) {
        return new HostLink("named", name, null, null, null, null);
    }

    /**
     * Terminates the channel on the plugin layer, naming it — or leaving the
     * name derived, for the no-argument {@code output()}.
     */
    @Nonnull
    public static HostLink output(@Nullable String name) {
        return new HostLink("sink", name, null, null, null, null);
    }

    /**
     * Ends the chain in a call to {@code target}, whose outcomes arrive on the
     * entry channel {@code response}.
     */
    @Nonnull
    public static HostLink call(@Nonnull CallTarget target, long deadlineMs, @Nonnull String response) {
        Map<String, Object> call = new LinkedHashMap<>();
        if (target.target() != null) {
            call.put("target", target.target());
        } else {
            call.put("factory", target.factory());
        }
        call.put("deadline_ms", deadlineMs);
        call.put("create", target.create());
        if (target.channel() != null) {
            call.put("channel", target.channel());
        }
        if (target.answerChannel() != null) {
            call.put("answer_channel", target.answerChannel());
        }
        call.put("response", response);
        return new HostLink("call", null, null, null, null, Collections.unmodifiableMap(call));
    }

    /** Closes the chain onto an edge into a tasklet index. */
    @Nonnull
    public static HostLink into(int tasklet) {
        return new HostLink("into", null, tasklet, null, null, null);
    }

    /** Closes the chain as one source of merge {@code group} into a tasklet. */
    @Nonnull
    public static HostLink merge(int tasklet, int group) {
        return new HostLink("merge", null, tasklet, group, null, null);
    }

    /** The {@code branch(...)} sugar's declared arity. */
    @Nonnull
    public static HostLink branch(int arms) {
        return new HostLink("branch", null, null, null, arms, null);
    }

    /** The tasklet this link wires its chain into, or {@code null}. */
    @Nullable
    public Integer target() {
        return link.equals("into") || link.equals("merge") ? tasklet : null;
    }

    /** This link as the record's JSON object. */
    @Nonnull
    public Map<String, Object> json() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("link", link);
        if (name != null) {
            out.put("name", name);
        }
        if (tasklet != null) {
            out.put("tasklet", tasklet);
        }
        if (group != null) {
            out.put("group", group);
        }
        if (arms != null) {
            out.put("arms", arms);
        }
        if (call != null) {
            out.putAll(call);
        }
        return out;
    }
}
