package com.flezha.sdk.tasklet;

import java.util.Optional;
import javax.annotation.Nullable;

/**
 * The keyed-channel contract, in one place: which key an emit carries, given
 * the channel's declaration and the Cell it came from. Every
 * {@link TaskletCtx} implementation resolves through here — the runtime
 * dispatcher and the test harness alike — so an author's harness-green test
 * cannot pass under a rule the runtime does not apply.
 *
 * <p>The Rust SDK gets this for free: its single emit funnel serves both sinks.
 * Java's ctx is an interface with independent implementations, so the funnel is
 * this method.
 */
public final class EmitKeys {

    private EmitKeys() {}

    /**
     * Resolve the key of one emit. On a keyed channel a supplied key wins and an
     * absent one falls back to {@code cellKey} — equal to the routing key by
     * construction, so the stamped key cannot drift from it; a single-cell
     * Tasklet has neither and is refused. A non-keyed channel carries no key
     * and refuses one.
     *
     * @return the key to carry, or {@code null} on a non-keyed channel
     * @throws TaskletException.KeyRequiredOnKeyedChannel a keyed channel with no key to carry
     * @throws TaskletException.KeyNotAllowedOnNonKeyedChannel a key on a non-keyed channel
     */
    @Nullable
    public static String resolve(String channel, boolean keyed, @Nullable String supplied, Optional<String> cellKey)
            throws TaskletException {
        if (!keyed) {
            if (supplied != null) {
                throw new TaskletException.KeyNotAllowedOnNonKeyedChannel(channel);
            }
            return null;
        }
        if (supplied != null) {
            return supplied;
        }
        return cellKey.orElseThrow(() -> new TaskletException.KeyRequiredOnKeyedChannel(channel));
    }
}
