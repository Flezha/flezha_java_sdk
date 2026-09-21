package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code CandleTasklet}: a trade stream keyed by
 * an EVENT time carried in the input itself ({@code "<event_time>:<price>"}).
 * One row per candle bucket, so a corrected replay overwrites by key rather than
 * appending a second row.
 */
public final class CandleTasklet
        implements Tasklet<String, CandleState, String, CandleChange, EventTimeFrom, Void> {

    @Override
    public String name() {
        return "candle_tasklet";
    }

    @Override
    public long eventTime(String input) {
        int at = input.indexOf(':');
        String ts = at < 0 ? input : input.substring(0, at);
        try {
            return Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public boolean eventTimeOverridden() {
        return true;
    }

    @Override
    public CandleState initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new CandleState();
    }

    @Override
    public void applyChange(CandleState state, CandleChange change) {
        state.folded.add(change.eventTime());
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(CandleState.class, CandleChange.class, ArrayList.class);
    }

    @Override
    public void run(String input, StateHandle<CandleState, CandleChange> state, TaskletCtx ctx)
            throws TaskletException {
        // `ctx.now()` IS the extracted event time under an EventTimeFrom clock.
        long eventTime = ctx.now();
        state.apply(new CandleChange(eventTime));
        long bucket = eventTime / 1_000;
        ctx.emitKeyed("candle/" + bucket, bucket + "=" + input);
    }
}
