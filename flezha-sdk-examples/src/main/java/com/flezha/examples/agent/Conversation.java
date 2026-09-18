package com.flezha.examples.agent;

import com.flezha.sdk.authoring.State;
import java.util.ArrayList;
import java.util.List;

/** The whole conversation. Being durable is what makes the agent resumable. */
@State
public class Conversation {
    public List<String> turns = new ArrayList<>();
}
