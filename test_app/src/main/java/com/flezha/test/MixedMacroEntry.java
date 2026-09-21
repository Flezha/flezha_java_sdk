package com.flezha.test;

import com.flezha.sdk.authoring.Tasklet;

/** Sugared pass-through entry tasklet of {@link MixedStreamOperative}. */
@Tasklet(id = "mixed_macro_entry_tasklet")
public class MixedMacroEntry {
    public String run(String input) {
        return input;
    }
}
