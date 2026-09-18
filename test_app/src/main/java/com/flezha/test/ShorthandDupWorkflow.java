package com.flezha.test;

import com.flezha.sdk.authoring.WorkflowOperative;
import java.nio.charset.StandardCharsets;

/**
 * The other half of {@code shorthand_duplicate_id}: a workflow claiming the factory id
 * {@link ShorthandDupStream} holds, which is what proves the two kinds share one namespace.
 */
@WorkflowOperative(id = "plain", sink = "twin_sink")
public class ShorthandDupWorkflow {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
