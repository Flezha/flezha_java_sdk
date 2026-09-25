package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import java.nio.charset.StandardCharsets;

/**
 * The other half of {@code shorthand_duplicate_id}: a bounded operator claiming the factory id
 * {@link ShorthandDupUnbounded} holds, which is what proves the two kinds share one namespace.
 */
@BoundedOperator(id = "plain", sink = "twin_sink")
public class ShorthandDupBounded {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
