package com.flezha.test;

import com.flezha.sdk.authoring.WorkflowOperative;
import com.flezha.sdk.operative.LoopMode;

/**
 * The workflow short form with its loop mode written: one iteration per message on
 * {@code input}, each returning the text it received, and no completion after the first.
 */
@WorkflowOperative(
        id = "sugar_workflow_per_input",
        sink = "sugar_workflow_per_input_out",
        loopMode = LoopMode.PER_INPUT)
public class SugarWorkflowPerInput {
    public String run(String input) {
        return input;
    }
}
