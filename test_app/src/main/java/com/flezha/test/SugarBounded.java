package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import java.nio.charset.StandardCharsets;

/**
 * The bounded short form: one declaration is a whole {@code once} bounded operator, run on its
 * first message on {@code order} and completing after one run.
 */
@BoundedOperator(id = "sugar_bounded", sink = "sugar_bounded_placed", input = "order")
public class SugarBounded {
    public byte[] run(String order) {
        return order.getBytes(StandardCharsets.UTF_8);
    }
}
