package com.flezha.sdk.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

/**
 * The SDK's default payload codec, used wherever nothing names one: a {@code byte[]} as it is, a
 * {@code String} as its UTF-8 bytes, anything else as JSON. An entry channel decodes with it, and
 * an output, a spawned steps relay's first input and a completion result encode with it, so
 * what a tasklet sends reads the same on each.
 */
public final class PlainPayload {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlainPayload() {}

    /** The payload bytes of {@code value}; a value Jackson cannot write fails as an unchecked error. */
    public static byte[] encode(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "could not encode " + value.getClass().getName() + " as JSON: " + e.getMessage(), e);
        }
    }

    /**
     * {@code bytes} read as {@code type}, the inverse of {@link #encode}; bytes that are not its
     * JSON fail as an unchecked error.
     */
    @SuppressWarnings("unchecked")
    public static <T> T decode(byte[] bytes, Class<T> type) {
        if (type == byte[].class) {
            return (T) bytes;
        }
        if (type == String.class) {
            return (T) new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return JSON.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("entry channel: JSON deserialization failed: " + e.getMessage(), e);
        }
    }
}
