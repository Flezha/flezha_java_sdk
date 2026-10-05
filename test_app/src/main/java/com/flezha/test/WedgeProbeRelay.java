package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirror of the Rust test app's {@code WedgeProbeRelay} — the
 * {@code pool_wedge} fixture, carrying both ways a runner stops doing work.
 *
 * <p>{@code wedge} blocks the dispatch inside user code forever, so the SDK's
 * dispatch counter stops advancing while the heartbeat thread keeps reporting
 * the runner alive: the {@code runner_pool_wedged} signature.
 *
 * <p>{@code abort} ends its activation as violently as this host allows
 * ({@code Error}, not a {@code TaskletException}) WITHOUT emitting, once per
 * process. Whatever the host does with a violent failure, the Cell must be
 * served again and the aborting item must not produce twice.
 *
 * <p>Every other input emits {@code ok:<input>}, which is how the e2e proves
 * the relay produces again once the remedy has landed.
 */
public final class WedgeProbeRelay implements Relay {

    @Override
    public String factoryId() {
        return "wedge_probe";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(WedgeProbeTasklet.class).output("out");
    }

    @Tasklet.Spec(name = "wedge_probe_tasklet")
    public static final class WedgeProbeTasklet implements Tasklet<String, Void, String> {

        private static final AtomicBoolean ABORTED = new AtomicBoolean(false);

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if ("wedge".equals(input)) {
                // Never returns. The runner stays connected and heartbeating;
                // only its dispatch progress dies. Teardown is the stdin-EOF watcher.
                while (true) {
                    try {
                        Thread.sleep(3_600_000L);
                    } catch (InterruptedException ignored) {
                        // An interrupt must not release the wedge.
                    }
                }
            }
            if ("abort".equals(input) && ABORTED.compareAndSet(false, true)) {
                throw new AssertionError("wedge_probe: killing the worker holding this activation");
            }
            ctx.emit("ok:" + input);
        }
    }
}
