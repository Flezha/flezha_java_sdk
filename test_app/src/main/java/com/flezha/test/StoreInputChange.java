package com.flezha.test;

import com.flezha.sdk.state.Change;

/**
 * Per-input Change recorded by {@link StoreInputTasklet}: one text input to
 * append. Mirrors the Rust SDK's {@code StoreInputChange::PushText}.
 */
public record StoreInputChange(String text) implements Change<StoreInputTasklet.State> {

    @Override
    public void applyTo(StoreInputTasklet.State state) {
        state.text_inputs().add(text);
    }
}
