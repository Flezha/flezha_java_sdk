package com.flezha.examples.merged;

import com.flezha.sdk.authoring.State;

/** What the detector remembers: the threshold in force, and how often it has alerted. */
@State
public class DetectorState {
    /** Alert on nothing until the config channel says otherwise. */
    public double maxCelsius = Double.POSITIVE_INFINITY;

    public long alerts;
}
