package com.flezha.examples.merged;

import com.flezha.sdk.state.Change;

/** What the detector remembers: the threshold in force, and how often it has alerted. */
public class DetectorState {
    /** Alert on nothing until the config channel says otherwise. */
    public double maxCelsius = Double.POSITIVE_INFINITY;

    public long alerts;

    /** The config channel set a new threshold. */
    public record ThresholdSet(double maxCelsius) implements Change<DetectorState> {
        @Override
        public void applyTo(DetectorState state) {
            state.maxCelsius = maxCelsius;
        }
    }

    /** A reading went over the threshold. */
    public record Alerted() implements Change<DetectorState> {
        @Override
        public void applyTo(DetectorState state) {
            state.alerts++;
        }
    }
}
