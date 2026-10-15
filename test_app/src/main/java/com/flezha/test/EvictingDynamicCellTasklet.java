package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;

/**
 * {@link DynamicCellTasklet} evicting a Cell idle for two seconds, wired when a
 * run sets {@code DYN_TEST_TTL_MS}. An annotation value is a constant, so the
 * TTL is the one the eviction scenario arms rather than the variable's value.
 */
@Tasklet.Spec(name = "dynamic_cell_tasklet", idleTtlSeconds = 2)
public final class EvictingDynamicCellTasklet extends DynamicCellTasklet {}
