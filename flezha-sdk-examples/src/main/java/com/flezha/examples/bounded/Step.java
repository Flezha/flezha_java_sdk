package com.flezha.examples.bounded;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * What every step here shares: a string in, a string out, and an undo handle in state.
 *
 * <p>The steps implement {@link Tasklet} directly rather than through {@code @Tasklet}, because
 * {@code compensate} is a method of the interface, which an annotated class has no way to supply.
 */
abstract class Step implements Tasklet<String, Step.Handle, String, Step.Reserved, WallClock, Void> {

    /** Restored on resume, unlike emitted output, so this is where an undo handle belongs. */
    public static final class Handle {
        public String reservationId;
    }

    /** Records the undo handle, which is what restores it on resume. */
    public record Reserved(String reservationId) implements Change {}

    @Override
    public void applyChange(@Nonnull Handle state, @Nonnull Reserved change) {
        state.reservationId = change.reservationId();
    }

    @Override
    @Nonnull
    public Handle initState(@Nonnull Optional<String> cellKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return new Handle();
    }
}
