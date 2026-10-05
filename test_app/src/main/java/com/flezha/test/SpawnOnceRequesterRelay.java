package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.SpawnHandle;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code spawn_once_requester}: on each input it spawns
 * {@code typed_once_steps} once with no id, carrying the input as the JSON ticket that
 * steps relay's entry channel decodes, and prints the handle's result, because a handle's answer
 * is observable nowhere but in the tasklet that read it.
 */
public final class SpawnOnceRequesterRelay implements Relay {

    @Override
    public String factoryId() {
        return "spawn_once_requester";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(SpawnOnceRequesterTasklet.class).output("out");
    }

    @Tasklet.Spec(name = "spawn_once_requester")
    public static final class SpawnOnceRequesterTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {
            byte[] ticket = ("{\"text\":\"" + escape(input) + "\"}").getBytes(StandardCharsets.UTF_8);
            SpawnHandle handle = ctx.spawnSteps(TypedOnceRelay.FACTORY_ID, ticket);
            SpawnRequesterRelay.SpawnRequesterTasklet.print("once", handle);
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
