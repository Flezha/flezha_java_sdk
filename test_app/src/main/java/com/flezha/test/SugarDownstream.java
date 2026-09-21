package com.flezha.test;

import com.flezha.sdk.authoring.Tasklet;
import java.nio.charset.StandardCharsets;

/** Stateless downstream tasklet of the 2-tasklet reference operative. */
@Tasklet(id = "macro_static_downstream_tasklet")
public class SugarDownstream {
    public byte[] run(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
