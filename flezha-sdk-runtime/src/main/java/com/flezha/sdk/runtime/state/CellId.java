package com.flezha.sdk.runtime.state;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Wire identity of a Cell. Combines the operator-instance id (= controller's
 * {@code operator_id}), the Tasklet's name within the UnboundedOperator, and the cell
 * key (empty string for single-cell mode).
 *
 * <p>The controller keys each per-Cell WAL by this identity and echoes it
 * back verbatim as {@code RestoreInitState.cell_id}, so {@link #encodedBytes()}
 * MUST be byte-identical to the controller's {@code Flezha.State.Wire.encode_cell_id/3}.
 */
public record CellId(String instanceId, String taskletName, String cellKey) {

    public static CellId of(
            @Nonnull String instanceId, @Nonnull String taskletName, @Nonnull Optional<String> cellKey) {
        return new CellId(instanceId, taskletName, cellKey.orElse(""));
    }

    /** Slash-joined form used only as an in-process map key / log label. */
    @Nonnull
    public String encoded() {
        return instanceId + "/" + taskletName + "/" + cellKey;
    }

    /**
     * Structured binary identity consumed by the controller's
     * {@code decode_cell_id}:
     * {@code [rc_len:u8][rc][sh_len:u8][sh][pk_len:u16 BE][pk]}, where
     * {@code rc} = operator-instance id, {@code sh} = tasklet name, {@code pk} =
     * cell key (all UTF-8). Mirrors the Rust SDK's
     * {@code encode_cell_identity}.
     */
    @Nonnull
    public byte[] encodedBytes() {
        byte[] rc = instanceId.getBytes(StandardCharsets.UTF_8);
        byte[] sh = taskletName.getBytes(StandardCharsets.UTF_8);
        byte[] pk = cellKey.getBytes(StandardCharsets.UTF_8);
        if (rc.length > 255 || sh.length > 255) {
            throw new IllegalArgumentException("operator_class / tasklet_id must be <= 255 bytes");
        }
        if (pk.length > 0xFFFF) {
            throw new IllegalArgumentException("cell_key must be <= 65535 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(1 + rc.length + 1 + sh.length + 2 + pk.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) rc.length);
        buf.put(rc);
        buf.put((byte) sh.length);
        buf.put(sh);
        buf.putShort((short) pk.length);
        buf.put(pk);
        return buf.array();
    }
}
