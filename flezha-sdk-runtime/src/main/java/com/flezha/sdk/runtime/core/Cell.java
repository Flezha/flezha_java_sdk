package com.flezha.sdk.runtime.core;

import com.flezha.sdk.runtime.state.CellId;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.tasklet.Tasklet;

/** One live Cell: its own Tasklet instance, state, and codec. */
final class Cell {
    final int taskletId;
    final String cellKey;

    @SuppressWarnings("rawtypes")
    final Tasklet tasklet;

    Object state;
    final KryoCodec codec;
    final CellId id;

    @SuppressWarnings("rawtypes")
    Cell(int taskletId, String cellKey, Tasklet tasklet, Object state, KryoCodec codec, CellId id) {
        this.taskletId = taskletId;
        this.cellKey = cellKey;
        this.tasklet = tasklet;
        this.state = state;
        this.codec = codec;
        this.id = id;
    }
}
