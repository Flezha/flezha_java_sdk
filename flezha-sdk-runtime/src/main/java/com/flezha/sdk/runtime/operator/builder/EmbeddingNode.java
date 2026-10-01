package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;

/**
 * The tasklet behind a node that embeds a steps class, which the core never runs: each edge key
 * runs a stepped instance of the class instead. Its identity is the node's name, the class's
 * factory id by default.
 */
@Tasklet.Spec(name = "embedding")
public final class EmbeddingNode implements Tasklet<Object, Void, Object> {

    @Override
    public void run(Object input, Ctx<Void, Object> ctx) {}
}
