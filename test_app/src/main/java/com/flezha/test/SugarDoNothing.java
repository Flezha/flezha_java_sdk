package com.flezha.test;

import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;
import java.nio.charset.StandardCharsets;

/**
 * Single-procedure stateless short-form twin of {@link DoNothingProcedure}. The annotation
 * processor generates {@code SugarDoNothingProcedure} and {@code SugarDoNothingStreamOperative};
 * {@code Main} registers the latter.
 */
@Procedure(id = "sugar_do_nothing", kind = OperativeKind.STREAM, sink = "sugar_do_nothing_echo")
public class SugarDoNothing {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
