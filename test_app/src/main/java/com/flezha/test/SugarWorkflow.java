package com.flezha.test;

import com.flezha.sdk.authoring.WorkflowOperative;
import java.nio.charset.StandardCharsets;

/**
 * The workflow short form: one declaration is a whole {@code once} workflow, seeded on
 * {@code order} from its start values and completing after one run.
 */
@WorkflowOperative(id = "sugar_workflow", sink = "sugar_workflow_placed", input = "order")
public class SugarWorkflow {
    public byte[] run(String order) {
        return order.getBytes(StandardCharsets.UTF_8);
    }
}
