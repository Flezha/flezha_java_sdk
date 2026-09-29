package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;

/**
 * The tasklets of {@code failing_turn_loop_bounded}, mirroring the Rust test
 * app's {@code Note} / {@code Answer}: {@code note} records each message and
 * its undo moves the latest one to {@code undone}; {@code answer} replies
 * {@code re:<message>}, and fails on {@code boom} always, on {@code boom-once}
 * while the release env is unset (the first runner of a crash drill), and on
 * {@code boom-until-healed} until a {@code heal} turn set its state.
 */
public final class FailingTurnSteps {

    private FailingTurnSteps() {}

    /** What {@link Note} recorded, and what its undo took back. */
    public static final class NoteLog {
        public final List<String> notes = new ArrayList<>();
        public final List<String> undone = new ArrayList<>();
    }

    /** Records a message, or moves the latest one to {@code undone}. */
    public record NoteChange(String noted, boolean undo) implements Change<NoteLog> {
        @Override
        public void applyTo(NoteLog state) {
            if (!undo) {
                state.notes.add(noted);
            } else if (!state.notes.isEmpty()) {
                state.undone.add(state.notes.remove(state.notes.size() - 1));
            }
        }
    }

    @Tasklet.Spec(name = "note")
    public static final class Note implements Tasklet<String, NoteLog, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(NoteLog.class, NoteChange.class, ArrayList.class);
        }

        @Override
        public void run(String input, Ctx<NoteLog, String> ctx) throws TaskletException {
            ctx.change(new NoteChange(input, false));
            ctx.emit(input);
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<NoteLog, String> ctx)
                throws TaskletException {
            ctx.change(new NoteChange("", true));
        }
    }

    /** Whether a {@code heal} turn ran. */
    public static final class Healed {
        public boolean healed;
    }

    /** Marks {@link Healed}. */
    public record Heal() implements Change<Healed> {
        @Override
        public void applyTo(Healed state) {
            state.healed = true;
        }
    }

    @Tasklet.Spec(name = "answer")
    public static final class Answer implements Tasklet<String, Healed, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(Healed.class, Heal.class);
        }

        @Override
        public void run(String input, Ctx<Healed, String> ctx) throws TaskletException {
            boolean fails = "boom".equals(input)
                    || ("boom-once".equals(input) && !BoundedE2eGate.released())
                    || ("boom-until-healed".equals(input) && !ctx.state().healed);
            if (fails) {
                throw new TaskletException("answer refused " + input);
            }
            if ("heal".equals(input)) {
                ctx.change(new Heal());
            }
            ctx.emit("re:" + input);
        }
    }
}
