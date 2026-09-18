package com.flezha.examples.spawn;

import com.flezha.sdk.authoring.Procedure;
import com.flezha.sdk.procedure.IfRunning;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.procedure.SpawnOptions;
import java.nio.charset.StandardCharsets;

/**
 * Watches the stream and asks for a triage worker per subject.
 *
 * <p>{@code run} takes the context because it does more than return a value: it spawns, and it
 * emits only for some inputs. {@code emits} declares the output type, which a {@code void} return
 * cannot.
 */
@Procedure(id = "detect", emits = String.class)
public final class Detect {

    public void run(String input, ProcedureCtx ctx) throws ProcedureException {
        if (!input.startsWith("alert:")) {
            return;
        }
        String subject = input.substring("alert:".length());

        // One worker per subject, so the subject names it. The handle is ignored on purpose: a
        // refused request unread by the end of this invocation logs itself, and this procedure
        // carries on detecting.
        ctx.spawnWorkflow(
                "triage",
                input.getBytes(StandardCharsets.UTF_8),
                SpawnOptions.id("triage@" + subject).ifRunning(IfRunning.DELIVER));

        ctx.emit("detected:" + subject);
    }
}
