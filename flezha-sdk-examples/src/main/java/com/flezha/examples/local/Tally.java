package com.flezha.examples.local;

import com.flezha.sdk.authoring.Tasklet;

/** The running count for one word. Each distinct word is its own cell. */
@Tasklet(id = "tally")
public final class Tally {

    public String run(String word, CountHandle count) {
        count.setSeen(count.get().seen + 1);
        return word + " " + count.get().seen;
    }
}
