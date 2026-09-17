package com.flezha.test;

import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;
import java.nio.charset.StandardCharsets;

/**
 * The workflow short form: one declaration is a whole {@code once} workflow, seeded on
 * {@code order} from its start values and completing after one run.
 */
@Procedure(
        id = "sugar_workflow",
        kind = OperativeKind.WORKFLOW,
        sink = "sugar_workflow_placed",
        input = "order")
public class SugarWorkflow {
    public byte[] run(String order) {
        return order.getBytes(StandardCharsets.UTF_8);
    }
}
