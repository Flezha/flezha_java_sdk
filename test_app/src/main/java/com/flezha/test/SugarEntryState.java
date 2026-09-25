package com.flezha.test;

import com.flezha.sdk.authoring.State;
import java.util.ArrayList;
import java.util.List;

/** Entry-tasklet state for the 2-tasklet reference operator. */
@State
public class SugarEntryState {
    public List<String> seen = new ArrayList<>();
}
