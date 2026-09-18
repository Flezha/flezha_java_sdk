package com.flezha.test;

import com.flezha.sdk.authoring.StreamOperative;
import java.nio.charset.StandardCharsets;

/**
 * Half of the refusal table's {@code shorthand_duplicate_id} pair. Not a fixture: nothing but that
 * row and the test beside it registers this class.
 */
@StreamOperative(id = "plain", sink = "out")
public class ShorthandDupStream {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
