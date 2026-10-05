package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code optout_commit_block}: the opt-out twin of
 * {@link CommitBlockRelay} with a live mid-chain signal — task A's
 * output also reaches a plugin output, so the crash-resume e2e can observe the
 * mid-run point without durable state (there is none). A crash + resume re-runs
 * the whole chain from the top, so the output sees task A's marker once per run
 * and no journal/WAL file exists.
 */
public final class OptOutCommitBlockRelay implements Relay {

    @Override
    public String factoryId() {
        return "optout_commit_block";
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return new Durable.Memory();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(CommitBlockRelay.CommitA.class)
                .output("mid", s -> ("a-ran:" + s).getBytes(StandardCharsets.UTF_8))
                .into(CommitBlockRelay.BlockB.class);
    }
}
