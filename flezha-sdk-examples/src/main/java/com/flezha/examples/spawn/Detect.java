package com.flezha.examples.spawn;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.IfRunning;
import com.flezha.sdk.tasklet.SpawnOptions;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Watches the stream and asks for a triage worker per subject.
 *
 * <p>Spawning and emitting both go through the context, and this tasklet emits only for some
 * inputs: an input that is not an alert returns without emitting anything.
 */
@Tasklet.Spec(name = "detect")
public final class Detect implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        if (!input.startsWith("alert:")) {
            return;
        }
        String subject = input.substring("alert:".length());

        // One worker per subject, so the subject names it. The handle is ignored on purpose: a
        // refused request unread by the end of this invocation logs itself, and this tasklet
        // carries on detecting.
        ctx.spawnBounded(
                "triage",
                input.getBytes(StandardCharsets.UTF_8),
                SpawnOptions.id("triage@" + subject).ifRunning(IfRunning.DELIVER));

        ctx.emit("detected:" + subject);
    }
}
