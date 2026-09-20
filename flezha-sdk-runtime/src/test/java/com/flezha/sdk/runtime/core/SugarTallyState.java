package com.flezha.sdk.runtime.core;

import com.flezha.sdk.authoring.Journal;
import com.flezha.sdk.authoring.State;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One field per shape the generator derives a variant from — a whole value, a
 * count, a per-key value and a per-key count — so a round trip over the
 * generated {@code SugarTallyStateChange} covers every record it emits.
 */
@State
public class SugarTallyState {
    public long n;

    public List<String> seen = new ArrayList<>();

    public Deque<String> window = new ArrayDeque<>();

    @Journal(Journal.Mode.MAP_VEC)
    public Map<String, List<String>> byKey = new HashMap<>();
}
