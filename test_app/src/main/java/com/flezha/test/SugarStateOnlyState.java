package com.flezha.test;

import com.flezha.sdk.authoring.State;
import java.util.ArrayList;
import java.util.List;

/** Derives {@code SugarStateOnlyStateChange} for {@link SugarStateOnly}. */
@State
public class SugarStateOnlyState {
    public List<String> seen = new ArrayList<>();
}
