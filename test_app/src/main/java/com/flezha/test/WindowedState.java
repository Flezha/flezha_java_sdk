package com.flezha.test;

import com.flezha.sdk.authoring.Journal;
import com.flezha.sdk.authoring.State;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-cell state whose window length comes from the cell key (see {@link Windowed}). */
@State
public class WindowedState {
    public int window;

    @Journal(Journal.Mode.MAP_VEC)
    public Map<String, List<Double>> closes = new HashMap<>();
}
