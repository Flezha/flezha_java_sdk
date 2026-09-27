package com.flezha.examples.merged;

import com.flezha.examples.merged.DetectorState.Alerted;
import com.flezha.examples.merged.DetectorState.ThresholdSet;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** Alerts when a reading goes over the threshold it was last told about. */
@Tasklet.Spec(name = "detector")
public final class Detector implements Tasklet<DetectorInput, DetectorState, String> {

    @Override
    public void run(DetectorInput input, Ctx<DetectorState, String> ctx) throws TaskletException {
        if (input.threshold().isPresent()) {
            ctx.change(new ThresholdSet(input.threshold().get().maxCelsius()));
            return;
        }

        Reading r = input.reading().orElseThrow();
        if (r.celsius() > ctx.state().maxCelsius) {
            ctx.change(new Alerted());
            ctx.emit(r.sensor() + " over threshold at " + r.celsius() + "C");
        }
    }
}
