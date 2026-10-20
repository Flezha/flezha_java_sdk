package com.flezha.sdk.tasklet;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.Logger;
import com.flezha.sdk.internal.InvocationOrigin;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The seam between {@link Ctx#spawnSteps} and the control plane. The runtime installs a
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
     * Issue the request; an empty {@code id} asks the controller to generate one, and a {@code null}
     * {@code ifRunning} sends no policy, the only choice the core accepts without an id. With no requester
     * installed there is no core to ask, so the handle answers what the core would: skipped on
     * replay, otherwise unavailable, with one warning here because no core reports it unread. The
     * activation and the input offset are {@code ctx}'s {@link InvocationOrigin}, none when it has
     * no origin.
     */
    @Nonnull
    public static SpawnHandle request(
            Ctx<?, ?> ctx, String factoryId, String id, byte[] payload, @Nullable IfRunning ifRunning) {
        boolean replay = ctx.replay();
        long token = ctx instanceof InvocationOrigin origin ? origin.activationToken() : 0;
        long offset = ctx instanceof InvocationOrigin origin ? origin.inputOffset() : -1;
        Requester current = requester;
        if (current == null) {
            if (replay) {
                ObjectNode answer = JsonNodeFactory.instance.objectNode().put("id", id);
                String json = answer.put("outcome", "skipped_on_replay").toString();
                return new SpawnHandle(timeoutMs -> json);
            }
            LOG.warn(
                    "spawnSteps(factory `{}`, id `{}`) failed: {}",
                    factoryId,
                    id,
                    FlezhaException.of("spawn.unavailable", Map.of()).getMessage());
            return new SpawnHandle(timeoutMs -> {
                throw FlezhaException.of("spawn.unavailable", Map.of());
            });
        }
        long handle = current.request(
                token, factoryId, id, payload, ifRunning == null ? "" : ifRunning.wire(), replay, offset);
        return new SpawnHandle(timeoutMs -> current.result(handle, timeoutMs));
    }
}
