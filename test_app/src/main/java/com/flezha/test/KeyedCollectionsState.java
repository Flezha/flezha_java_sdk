package com.flezha.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-key latest value and per-key closes, plus a flat field. */
public class KeyedCollectionsState {
    public Map<String, Double> last = new HashMap<>();

    public Map<String, List<Double>> closes = new HashMap<>();

    public long ticks;
}
