package com.flezha.sdk.tasklet;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The seam between {@link Ctx#spawnBounded} and the control plane. The runtime installs a
 * {@link Requester} when it starts the core; the api layer, which cannot see the core, routes every
 * request through it. The core owns the rest: the replay skip, the deadline, the release of a handle
 * when its activation ends, and the warning for a failure nobody read.
 */
public final class SpawnRequests {

    private static final Logger LOG = Flezha.log();

    /** The core's two spawn entries. */
    public interface Requester {
        /** Ship one request from the activation {@code token}; the core's handle number, at once. */
        long request(
                long token, String factoryId, String id, byte[] payload, String ifRunning, boolean replay, long offset);

        /** Read {@code handle}, waiting up to {@code timeoutMs}: its answer as JSON, {@code null} while pending. */
        @Nullable
        String result(long handle, long timeoutMs);
    }

    private static volatile Requester requester;

    private SpawnRequests() {}

    /** Install the control-plane requester. A later install replaces the earlier one. */
    public static void install(Requester newRequester) {
        requester = newRequester;
    }

    /** Drop the installed requester (runner close, and test isolation). */
    public static void uninstall() {
        requester = null;
    }

    /**
     * Issue the request; an empty {@code id} asks the controller to generate one. With no requester
     * installed there is no core to ask, so the handle answers what the core would: skipped on
     * replay, otherwise unavailable, with one warning here because no core reports it unread. {@code
     * offset} is the input-log offset of the item the request runs under, or -1 for none.
     */
    @Nonnull
    public static SpawnHandle request(
            long token, String factoryId, String id, byte[] payload, IfRunning ifRunning, boolean replay, long offset) {
        Requester current = requester;
        if (current == null) {
            ObjectNode answer = JsonNodeFactory.instance.objectNode().put("id", id);
            if (replay) {
                answer.put("outcome", "skipped_on_replay");
            } else {
                String message = "no control plane to request a spawn on";
                answer.put("error", "unavailable").put("message", message);
                LOG.warn("spawnBounded(factory `{}`, id `{}`) failed: {}", factoryId, id, message);
            }
            String json = answer.toString();
            return new SpawnHandle(timeoutMs -> json);
        }
        long handle = current.request(token, factoryId, id, payload, ifRunning.wire(), replay, offset);
        return new SpawnHandle(timeoutMs -> current.result(handle, timeoutMs));
    }
}
