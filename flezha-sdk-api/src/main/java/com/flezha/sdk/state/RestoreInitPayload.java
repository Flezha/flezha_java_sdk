package com.flezha.sdk.state;

/**
 * A cell's restore payload as the test harness replays it: the latest snapshot bytes (an empty
 * array for "no snapshot") and the change log since that snapshot, a Kryo-encoded list of
 * changes (an empty array for none). The runtime builds the initial state first, then applies the
 * snapshot and folds each change through its {@code applyTo}.
 */
public record RestoreInitPayload(byte[] snapshotBytes, byte[] changeLogBytes) {}
