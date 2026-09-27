package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;

/**
 * Shared base for the crash-resume e2e's stateless terminal {@code String} steps
 * (§7c.3). A concrete task declares only its {@code @Tasklet.Spec} name and
 * {@code run} (which blocks or fails the chain). Mirrors the Rust test app's
 * {@code stateless_step!} macro.
 */
public abstract class NoStateStep implements Tasklet<String, Void, String> {}
