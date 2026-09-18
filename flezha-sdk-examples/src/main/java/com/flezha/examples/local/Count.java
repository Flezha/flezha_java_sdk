package com.flezha.examples.local;

import com.flezha.sdk.authoring.State;

/**
 * How often one word has been seen. {@code @State} generates {@code CountHandle}, whose
 * {@code setSeen} records the change rather than assigning the field.
 */
@State
public class Count {
    public long seen;
}
