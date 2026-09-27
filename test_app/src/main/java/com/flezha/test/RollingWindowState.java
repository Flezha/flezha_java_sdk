package com.flezha.test;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Canonical keyed-collection reference state: a rolling per-key window. */
public class RollingWindowState {
    public Map<String, Deque<Double>> window = new HashMap<>();
}
