package com.flezha.test;

import com.flezha.sdk.authoring.UnboundedOperator;
import java.nio.charset.StandardCharsets;

/**
 * Single-tasklet stateless short-form twin of {@link DoNothingTasklet}. The annotation
 * processor generates {@code SugarDoNothingTasklet} and {@code SugarDoNothingUnboundedOperator};
 * {@code Main} registers this class.
 */
@UnboundedOperator(id = "sugar_do_nothing", sink = "sugar_do_nothing_echo")
public class SugarDoNothing {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
