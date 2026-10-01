package com.flezha.test;

import com.flezha.sdk.authoring.Operator;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;

/**
 * The tasklet palette {@link ConformanceRun} binds the fixture's roles to —
 * one class per role of {@code test/fixtures/reference_topologies.txt}, each
 * declaring exactly the facts a rule reads.
 *
 * <p>These are not fixtures: no capability lists them, {@code Main} never
 * defines them, and nothing but a row of the refusal table reaches them. They
 * live beside the interpreter because a role IS a tasklet declaration, and
 * one written elsewhere would be read as a fixture.
 *
 * <p>Every role but {@code nochange} keeps a state its Change applies to, so
 * a row's class has something to keep wherever its {@code class.durable} step
 * says the work is kept.
 */
final class Palette {

    /** The mailbox capacity every role declares unless its row writes one. */
    static final int MAILBOX = 1024;

    /** The capacity the fixture's {@code mailbox_huge} row declares on {@code plain}. */
    static final int OVERSIZED_MAILBOX = 2_000_000;

    private Palette() {}

    /** The state the journalling roles keep; nothing reads it. */
    static final class Log {}

    /** The Change the journalling roles record; its content is never read. */
    record Journalled(String value) implements Change<Log> {
        @Override
        public void applyTo(Log log) {}
    }

    /** A role with the shared declaration: single mode, wall clock, and a state the class persists. */
    abstract static class Role implements Tasklet<String, Log, String> {

        @Override
        public void run(String input, Ctx<Log, String> ctx) {}
    }

    @Tasklet.Spec(name = "plain", capacity = MAILBOX)
    public static final class Plain extends Role {}

    /** {@code plain} with a mailbox past the ceiling. */
    @Tasklet.Spec(name = "plain", capacity = OVERSIZED_MAILBOX)
    public static final class PlainOversized extends Role {}

    /** {@code plain} with a mailbox of zero. */
    @Tasklet.Spec(name = "plain", capacity = 0)
    public static final class PlainWithoutMailbox extends Role {}

    @Tasklet.Spec(name = "second", capacity = MAILBOX)
    public static final class Second extends Role {}

    /** A distinct class declaring the SAME name as {@code plain}: the collision. */
    @Tasklet.Spec(name = "plain", capacity = MAILBOX)
    public static final class Twin extends Role {}

    /** Static mode with no router: {@code cellsFor} is deliberately absent. */
    @Tasklet.Spec(name = "static", keys = {"a", "b"}, capacity = MAILBOX)
    public static final class StaticKeys extends Role {}

    /** Static mode with its router declared. */
    @Tasklet.Spec(name = "routed", keys = {"a", "b"}, capacity = MAILBOX)
    public static final class Routed extends Role {

        @Override
        public List<String> cellsFor(String input) {
            return List.of("a");
        }
    }

    /**
     * Dynamic mode is read off the WIRING in this SDK — a tasklet a keyed
     * chain closes onto is dynamic — so the role's own declaration is a plain
     * one.
     */
    @Tasklet.Spec(name = "dynamic", capacity = MAILBOX)
    public static final class Dynamic extends Role {}

    /** A logical clock over a tasklet that keeps no state, so journals nothing. */
    @Tasklet.Spec(name = "nochange", logicalClock = true, capacity = MAILBOX)
    public static final class LogicalWithoutState implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    /** An event-time clock, declared by writing the extractor. */
    @Tasklet.Spec(name = "eventtime", capacity = MAILBOX)
    public static final class EventTime extends Role {

        @Override
        public long eventTime(String input) {
            return input.length();
        }
    }

    /** {@code shorthand(plain, out, -).topology}: a short form that also implements the operator interface. */
    @Operator(id = "plain", sink = "out")
    public static final class ShortFormWithTopology
            implements Tasklet<String, Void, String>, com.flezha.sdk.operator.Operator {

        @Override
        public String factoryId() {
            return "plain";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(ShortFormWithTopology.class).sink("out");
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    /** {@code shorthand(plain, out, -).noout}: a short form with a sink and no output. */
    @Operator(id = "plain", sink = "out")
    public static final class SinkWithoutOutput implements Tasklet<String, Void, Void> {

        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }
}
