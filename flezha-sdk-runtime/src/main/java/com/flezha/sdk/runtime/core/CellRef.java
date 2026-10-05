package com.flezha.sdk.runtime.core;

import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * The Cell an activation is addressed to. {@code cellKey} is empty only
 * for pre-keying work ({@link ActivationKind.KeyRequest}), where the key is
 * what's being computed.
 */
public record CellRef(@Nonnull String relay, int tasklet, @Nonnull Optional<String> cellKey) {}
