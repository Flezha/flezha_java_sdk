package com.flezha.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-cell state whose window length comes from the cell key (see {@link Windowed}). */
public class WindowedState {
    public int window;

    public Map<String, List<Double>> closes = new HashMap<>();
}
