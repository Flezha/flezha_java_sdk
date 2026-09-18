package com.flezha.examples.spawn;

import com.flezha.sdk.authoring.Procedure;

/** Triages the alert the worker was spawned for. */
@Procedure(id = "triage")
public final class Triage {

    public String run(String alert) {
        return "triaged:" + alert;
    }
}
