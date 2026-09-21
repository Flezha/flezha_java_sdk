package com.flezha.examples.pipeline;

import com.flezha.sdk.authoring.Tasklet;

/**
 * The first stage: normalises the raw line before any later stage observes it.
 *
 * <p>Deliberately trivial, so that the wiring rather than the logic is what the example teaches. A
 * stage has no knowledge of what follows it, which is what allows one to be reordered or reused in
 * another graph without modification.
 */
@Tasklet(id = "normalize")
public final class Normalize {

    public String run(String input) {
        return input.trim().toLowerCase();
    }
}
