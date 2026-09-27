package com.flezha.examples.local;

/**
 * How often one word has been seen. {@link Tally} writes it only through its {@code Counted}
 * change, never by assigning the field.
 */
public class Count {
    public long seen;
}
