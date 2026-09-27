package com.flezha.sdk.runtime.core;

import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.tasklet.SpawnRequests;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The {@link SpawnRequests.Requester} behind {@code ctx.spawnBounded}: the poll core's two spawn
 * entries over one context. The request never waits, and the core holds the handle, so a read runs
 * on the thread that asks. Installed by the runtime at {@code start()}.
 */
public final class CoreSpawnRequester implements SpawnRequests.Requester {

    private final long ctx;

    public CoreSpawnRequester(long ctx) {
        this.ctx = ctx;
    }

    @Override
    public long request(
            long token,
            @Nonnull String factoryId,
            @Nonnull String id,
            @Nonnull byte[] payload,
            @Nonnull String ifRunning,
            boolean replay,
            long offset) {
        return FlzCore.spawnRequest(ctx, token, factoryId, id, payload, ifRunning, replay, offset);
    }

    @Override
    @Nullable
    public String result(long handle, long timeoutMs) {
        return FlzCore.spawnResult(ctx, handle, timeoutMs);
    }
}
