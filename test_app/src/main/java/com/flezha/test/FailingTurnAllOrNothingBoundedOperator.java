package com.flezha.test;

import com.flezha.sdk.operator.RollbackMode;

/** {@code failing_turn_loop_bounded} under all-or-nothing, which still ends on a failed turn. */
public final class FailingTurnAllOrNothingBoundedOperator extends FailingTurnLoopBoundedOperator {

    @Override
    public String factoryId() {
        return "failing_turn_all_or_nothing_bounded";
    }

    @Override
    public RollbackMode rollbackMode() {
        return RollbackMode.ALL_OR_NOTHING;
    }
}
