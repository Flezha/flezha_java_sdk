package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Mirror of the Rust test app's {@code effect_block_steps}: the one tasklet calls an outside
 * function through {@code ctx.effect}, then blocks until released. The input names a file the
 * function appends one line to per run, so a SIGKILL after the effect and a resume on a released
 * runner shows whether the function ran again.
 */
public final class EffectBlockRelay implements Relay {

    @Override
    public String factoryId() {
        return "effect_block_steps";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(EffectBlock.class);
    }

    static String countRun(String path) {
        try {
            Files.writeString(
                    Path.of(path), "ran\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "answer";
    }

    @Tasklet.Spec(name = "effect_block")
    public static final class EffectBlock extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.effect("model", String.class, () -> countRun(input));
            StepsE2eGate.blockUntilReleased();
        }
    }
}
