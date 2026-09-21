package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.LogicalClock;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

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
 * <p>Every role but {@code nochange} journals a Change, which is what makes
 * the class persistent in this SDK: Java reads persistence off the Change
 * types its tasklets declare rather than off a class flag, so a row's
 * {@code class.persistent} step is what the palette already says.
 */
final class Palette {

    private Palette() {}

    /** The Change the journalling roles record; its content is never read. */
    record Journalled(String value) implements Change {}

    /**
     * A role with the shared declaration: single mode, wall clock, and a
     * Change the class persists. The role names the fixture row's binding,
     * which is the tasklet's own name everywhere but {@code twin}.
     */
    abstract static class Role implements Tasklet<String, Stateless, String, Journalled, WallClock, Void> {

        abstract String role();

        @Override
        public String name() {
            return role();
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, Journalled> state, TaskletCtx ctx) {}

        @Override
        public int mailboxCapacity() {
            return ConformanceRun.mailboxOf(role());
        }

        @Override
        public boolean mailboxCapacityOverridden() {
            return true;
        }
    }

    public static final class Plain extends Role {
        @Override
        String role() {
            return "plain";
        }
    }

    public static final class Second extends Role {
        @Override
        String role() {
            return "second";
        }
    }

    /** A distinct class declaring the SAME name as {@code plain}: the collision. */
    public static final class Twin extends Role {
        @Override
        String role() {
            return "twin";
        }

        @Override
        public String name() {
            return "plain";
        }
    }

    /** Static mode with no router: {@code cellsFor} is deliberately absent. */
    public static final class StaticKeys extends Role {
        @Override
        String role() {
            return "static";
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b");
        }
    }

    /** Static mode with its router declared. */
    public static final class Routed extends Role {
        @Override
        String role() {
            return "routed";
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b");
        }

        @Override
        public boolean cellsForOverridden() {
            return true;
        }

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
    public static final class Dynamic extends Role {
        @Override
        String role() {
            return "dynamic";
        }
    }

    /** A logical clock over a tasklet that journals nothing. */
    public static final class NoChangeLogical
            implements Tasklet<String, Stateless, String, NoChange, LogicalClock, Void> {

        @Override
        public String name() {
            return "nochange";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx) {}

        @Override
        public int mailboxCapacity() {
            return ConformanceRun.mailboxOf("nochange");
        }

        @Override
        public boolean mailboxCapacityOverridden() {
            return true;
        }
    }

    /** An event-time clock, with the extractor erasure makes it declare here. */
    public static final class EventTime
            implements Tasklet<String, Stateless, String, Journalled, EventTimeFrom, Void> {

        @Override
        public String name() {
            return "eventtime";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, Journalled> state, TaskletCtx ctx) {}

        @Override
        public long eventTime(String input) {
            return input.length();
        }

        @Override
        public boolean eventTimeOverridden() {
            return true;
        }

        @Override
        public int mailboxCapacity() {
            return ConformanceRun.mailboxOf("eventtime");
        }

        @Override
        public boolean mailboxCapacityOverridden() {
            return true;
        }
    }
}
