package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * The bounded short form: one declaration is a whole {@code once} bounded operator, run on its
 * first message on {@code order} and completing after one run.
 */
@BoundedOperator(id = "sugar_bounded", sink = "sugar_bounded_placed", input = "order")
public class SugarBounded implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String order, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(order.getBytes(StandardCharsets.UTF_8));
    }
}
