package com.flezha.sdk;

/**
 * Thrown by {@link FlezhaRunner#unregisterFactory(Class)} when live operator instances of
 * the factory exist on the started runner — the removal does not take effect
 * and the type stays defined. Stop the instances (controller-side) before
 * removing the type. Mirrors the Rust SDK's
 * {@code FlezhaError::OperatorTypeInUse}.
 */
public final class OperatorTypeInUseException extends IllegalStateException {

    public OperatorTypeInUseException(String message) {
        super(message);
    }
}
