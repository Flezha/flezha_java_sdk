package com.flezha.sdk.runtime.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flezha.sdk.state.Change;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

/**
 * The state crossing, in both directions: restore bodies folded onto a cell,
 * the JSON shape the controller reads and writes, and the change buffer one
 * invocation produced. Every method is handed the cell it works on — the cell
 * map itself is {@link OperatorInstance}'s and stays there.
 */
final class StateExchange {

    /** Sorted-key, non-pretty JSON — the Decision-10 per-Cell state shape. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private StateExchange() {}

    /** Snapshot then changes, in order, onto a cell the caller has just made. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void foldInto(Cell cell, ActivationKind.RestoreFold fold) {
        if (fold.snapshot().isPresent()) {
            ActivationKind.RestoreBody body = fold.snapshot().get();
            cell.state = read(body, cell, Object.class, cell.tasklet::migrateState);
        }
        for (ActivationKind.RestoreBody body : fold.changes()) {
            Change change = read(body, cell, Change.class, cell.tasklet::migrateChange);
            change.applyTo(cell.state);
        }
    }

    /**
     * One restore body: decoded with this version's codec, or handed to
     * the migration the core tagged it for. The core has already refused
     * everything unreadable, so there is no third case.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T read(
            ActivationKind.RestoreBody body, Cell cell, Class<T> type, BiFunction<Integer, byte[], ?> migrate) {
        return body.fromVersion().isPresent()
                ? (T) migrate.apply(body.fromVersion().getAsInt(), body.bytes())
                : cell.codec.deserialize(body.bytes(), type);
    }

    static byte[] stateJson(Cell cell) {
        // A Void state has nothing to ship. It ships `{}`, and `applyState`
        // keeps it absent, so a stateless operator's migration serialize and
        // its shutdown state ship succeed.
        if (cell.state == null) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
        try {
            return JSON.writeValueAsBytes(cell.state);
        } catch (Exception e) {
            throw new IllegalStateException("state serialization failed: " + e.getMessage(), e);
        }
    }

    static void applyState(Cell cell, byte[] json) {
        if (cell.state == null) {
            return;
        }
        try {
            cell.state = JSON.readValue(json, cell.state.getClass());
        } catch (Exception e) {
            throw new IllegalStateException("state apply failed: " + e.getMessage(), e);
        }
    }
}
