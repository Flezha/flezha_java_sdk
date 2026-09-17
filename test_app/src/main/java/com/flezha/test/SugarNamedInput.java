package com.flezha.test;

import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;
import java.nio.charset.StandardCharsets;

/** The short form naming its entry channel {@code prices} rather than taking the default. */
@Procedure(
        id = "sugar_named_input",
        kind = OperativeKind.STREAM,
        sink = "sugar_named_input_sink",
        input = "prices")
public class SugarNamedInput {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
