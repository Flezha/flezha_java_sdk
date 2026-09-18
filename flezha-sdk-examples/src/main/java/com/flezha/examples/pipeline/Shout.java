package com.flezha.examples.pipeline;

import com.flezha.sdk.authoring.Procedure;

/**
 * The second stage. Its input is whatever the first stage emitted, so the declared types must
 * agree: {@code Normalize} emits a {@code String} and this consumes one.
 *
 * <p>Nothing here indicates a position in a chain. From this class's perspective messages simply
 * arrive, which is the same contract the first stage has.
 */
@Procedure(id = "shout")
public final class Shout {

    public String run(String input) {
        return input.toUpperCase() + "!";
    }
}
