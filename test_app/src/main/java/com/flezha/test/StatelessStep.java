package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;

/**
 * Shared base for the crash-resume e2e's stateless terminal {@code String} steps
 * (§7c.3). A concrete task declares only {@link #name()} and {@code run} (which
 * blocks or fails the chain). Mirrors the Rust test app's {@code stateless_step!}
 * macro.
 */
public abstract class StatelessStep
        implements Tasklet<String, StatelessStep.State, String, NoChange, WallClock, Void> {

    public record State() {}

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State();
    }
}
