package com.flezha.test;

import com.flezha.sdk.state.Change;

/** Upsert of one key's value into a {@link TierStoreState}. */
public record TierStoreChange(String key, String value) implements Change<TierStoreState> {

    @Override
    public void applyTo(TierStoreState state) {
        state.values.put(key, value);
    }
}
