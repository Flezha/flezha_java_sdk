package com.flezha.examples.local;

import com.flezha.sdk.authoring.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;

/** One line in, one emit per word out. */
@Procedure(id = "split", emits = String.class)
public final class Split {

    public void run(String line, ProcedureCtx ctx) throws ProcedureException {
        for (String word : line.split("\\s+")) {
            if (!word.isEmpty()) {
                ctx.emit(word.toLowerCase());
            }
        }
    }
}
