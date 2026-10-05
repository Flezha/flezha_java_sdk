package com.flezha.test;

/** {@code failing_turn_loop_steps} under all-or-nothing, which still ends on a failed turn. */
public final class FailingTurnUndoAllRelay extends FailingTurnLoopRelay {

    @Override
    public String factoryId() {
        return "failing_turn_undo_all_steps";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

}
