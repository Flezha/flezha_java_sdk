package com.flezha.examples.bounded;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Tasklet;

/**
 * What every step here shares: a string in, a string out, and an undo handle in state. Each step
 * is its own class with its own {@code @Tasklet.Spec} name; this class only fixes their types.
 */
abstract class Step implements Tasklet<String, Step.Handle, String> {

    /** Restored on resume, unlike emitted output, so this is where an undo handle belongs. */
    public static final class Handle {
        public String reservationId;

        /** Records the undo handle, which is what restores it on resume. */
        public record Reserved(String reservationId) implements Change<Handle> {
            @Override
            public void applyTo(Handle handle) {
                handle.reservationId = reservationId;
            }
        }
    }
}
