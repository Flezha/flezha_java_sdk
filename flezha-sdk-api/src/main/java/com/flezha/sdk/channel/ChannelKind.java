package com.flezha.sdk.channel;

/** Kind of channel — entry (controller → SDK), inter-tasklet, or plugin-bound (SDK → plugin). */
public enum ChannelKind {
    ENTRY,
    INTER_TASKLET,
    PLUGIN_BOUND
}
