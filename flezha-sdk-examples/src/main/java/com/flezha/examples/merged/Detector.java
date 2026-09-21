package com.flezha.examples.merged;

import com.flezha.sdk.authoring.Tasklet;
import java.util.Optional;

/** Alerts when a reading goes over the threshold it was last told about. */
@Tasklet(id = "detector")
public final class Detector {

    public Optional<String> run(DetectorInput input, DetectorStateHandle state) {
        if (input.threshold().isPresent()) {
            state.setMaxCelsius(input.threshold().get().maxCelsius());
            return Optional.empty();
        }

        Reading r = input.reading().orElseThrow();
        if (r.celsius() > state.get().maxCelsius) {
            state.setAlerts(state.get().alerts + 1);
            return Optional.of(r.sensor() + " over threshold at " + r.celsius() + "C");
        }
        return Optional.empty();
    }
}
