package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code CandleTasklet}: a trade stream keyed by
 * an EVENT time carried in the input itself ({@code "<event_time>:<price>"}).
 * One row per candle bucket, so a corrected replay overwrites by key rather than
 * appending a second row.
 */
@Tasklet.Spec(name = "candle_tasklet")
public final class CandleTasklet implements Tasklet<String, CandleState, String> {

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
    public List<Class<?>> kryoTypes() {
        return List.of(CandleState.class, CandleChange.class, ArrayList.class);
    }

    @Override
    public void run(String input, Ctx<CandleState, String> ctx) throws TaskletException {
        // `ctx.now()` IS the extracted event time, because the class writes eventTime(input).
        long eventTime = ctx.now();
        ctx.change(new CandleChange(eventTime));
        long bucket = eventTime / 1_000;
        ctx.emitKeyed("candle/" + bucket, bucket + "=" + input);
    }
}
