package com.flezha.test;

import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;
import java.nio.charset.StandardCharsets;

/**
 * Half of the refusal table's {@code shorthand_duplicate_id} pair. Not a fixture: nothing but that
 * row and the test beside it registers this class.
 */
@Procedure(id = "plain", kind = OperativeKind.STREAM, sink = "out")
public class ShorthandDupStream {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
