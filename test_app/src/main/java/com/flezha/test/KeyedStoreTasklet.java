package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.TreeMap;

/**
 * Shared base for the {@code key:value} upsert tasklets: {@link TierStoreTasklet},
 * {@link CheckpointStoreTasklet} and {@link BenchCheckpointTasklet} differ only
 * in their name and {@code run}. Mirrors the Rust test app's three tasklets
 * over one {@code TierStoreState} / {@code TierStoreChange} pair.
 */
public abstract class KeyedStoreTasklet implements Tasklet<String, TierStoreState, String> {

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(TierStoreState.class, TierStoreChange.class, TreeMap.class);
    }

    /** Split {@code "key:value"}; a value-less input keys on the whole string. */
    static String[] split(String input) {
        int at = input.indexOf(':');
        return at < 0 ? new String[] {input, ""} : new String[] {input.substring(0, at), input.substring(at + 1)};
    }
}
