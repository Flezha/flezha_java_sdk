package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;

/**
 * The tasklets the durable chat loops share ({@code chat_loop_steps},
 * {@code stateless_loop_steps}, {@code chat_rewind_steps} and the bench
 * loop), mirroring the Rust test app's {@code ChatTurn} / {@code ChatReply} /
 * {@code ChatReplyUndoable}.
 */
public final class ChatSteps {

    /** The turn whose payload BLOCKS while the release env is unset. */
    static final String CRASH_TURN = "mid-crash";

    /** The closing turn: not a turn of the conversation, it ends the session. */
    static final String CLOSING_TURN = "bye";

    private ChatSteps() {}

    /**
     * Commits the turn to Cell state and passes it on. The change is keyed by
     * the TURN, so the WAL record count is the number of DISTINCT turns that ran
     * — a re-run of a committed iteration would show up.
     *
     * <p>The closing turn is passed on unrecorded and ends the session with the
     * number of turns the state holds.
     */
    @Tasklet.Spec(name = "chat_turn")
    public static final class Turn extends DurableStep {
        @Override
        public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
            if (CLOSING_TURN.equals(input)) {
                ctx.emit(input);
                ctx.complete(Integer.toString(ctx.state().items.size()));
                return;
            }
            if (CRASH_TURN.equals(input)) {
                StepsE2eGate.blockUntilReleased();
            }
            ctx.change(new Insert(input));
            ctx.emit(input);
        }
    }

    /** The stateless terminal step: emits {@code reply:<input>}. */
    @Tasklet.Spec(name = "chat_reply")
    public static final class Reply extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("reply:" + input);
        }
    }

    /** What {@link ReplyUndoable} has said, newest last — the undo handle. */
    public static final class ReplyLog {
        public final List<String> said = new ArrayList<>();
    }

    /** Appends a turn to a {@link ReplyLog}, or pops the last one on a rewind. */
    public record ReplyChange(String turn, boolean retract) implements Change<ReplyLog> {

        static ReplyChange said(String turn) {
            return new ReplyChange(turn, false);
        }

        static ReplyChange retracted() {
            return new ReplyChange("", true);
        }

        @Override
        public void applyTo(ReplyLog state) {
            if (retract) {
                if (!state.said.isEmpty()) {
                    state.said.remove(state.said.size() - 1);
                }
            } else {
                state.said.add(turn);
            }
        }
    }

    /**
     * The replying tasklet a rewind can undo. A rewind's compensation is
     * rebuilt from the journal and is handed NO retained outputs, so the hook
     * reads the last thing it said out of Cell State, retracts it, and pops it
     * — unwinding in reverse iteration order retracts the turns in reverse.
     */
    @Tasklet.Spec(name = "chat_reply_undoable")
    public static final class ReplyUndoable implements Tasklet<String, ReplyLog, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(ReplyLog.class, ReplyChange.class, ArrayList.class);
        }

        @Override
        public void run(String input, Ctx<ReplyLog, String> ctx) throws TaskletException {
            ctx.change(ReplyChange.said(input));
            ctx.emit("reply:" + input);
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<ReplyLog, String> ctx) throws TaskletException {
            List<String> said = ctx.state().said;
            if (said.isEmpty()) {
                return;
            }
            String turn = said.get(said.size() - 1);
            ctx.change(ReplyChange.retracted());
            ctx.emit("retract:" + turn);
        }
    }
}
