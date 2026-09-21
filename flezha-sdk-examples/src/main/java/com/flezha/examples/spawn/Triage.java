package com.flezha.examples.spawn;

import com.flezha.sdk.authoring.Tasklet;

/** Triages the alert the worker was spawned for. */
@Tasklet(id = "triage")
public final class Triage {

    public String run(String alert) {
        return "triaged:" + alert;
    }
}
