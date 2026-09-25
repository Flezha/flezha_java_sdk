package com.flezha.test;

/**
 * Shared crash-window gate for the durable structured bounded crash-resume e2e
 * (§7c.3). A tasklet (or a compensation hook) parks a pool/driver thread
 * until {@code FLEZHA_BOUNDED_E2E_RELEASE} is present in the environment. The
 * first runner is launched WITHOUT it (so a SIGKILL lands while the operator
 * is mid-chain, with earlier tasklets' Cell state already durable); the
 * respawned runner is launched WITH it, so the resumed instance runs to
 * completion. Mirrors the Rust test app's {@code block_until_released}.
 */
final class BoundedE2eGate {

    static final String RELEASE_ENV = "FLEZHA_BOUNDED_E2E_RELEASE";

    /** Where a runner booted with this variable parks: a crash point the harness names. */
    static final String HOLD_ENV = "FLEZHA_BOUNDED_E2E_HOLD";

    private BoundedE2eGate() {}

    static boolean released() {
        return System.getenv(RELEASE_ENV) != null;
    }

    static void holdAt(String point) {
        if (point.equals(System.getenv(HOLD_ENV))) {
            blockUntilReleased();
        }
    }

    static void blockUntilReleased() {
        while (!released()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
