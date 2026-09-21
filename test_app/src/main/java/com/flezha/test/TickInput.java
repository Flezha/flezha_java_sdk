package com.flezha.test;

/** The {@code <interval>:<ts>:<delta>} wire format, parsed by {@link TickEntryTasklet}. */
public record TickInput(int interval, long ts, long delta) {
}
