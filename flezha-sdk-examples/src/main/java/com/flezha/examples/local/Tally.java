package com.flezha.examples.local;

import com.flezha.sdk.authoring.Procedure;

/** The running count for one word. Each distinct word is its own entity. */
@Procedure(id = "tally")
public final class Tally {

    public String run(String word, CountHandle count) {
        count.setSeen(count.get().seen + 1);
        return word + " " + count.get().seen;
    }
}
