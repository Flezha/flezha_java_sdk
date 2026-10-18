package com.flezha.sdk;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Standard error held for the life of a try block. The SDK's default log writes there, so a test
 * that provokes a log line asserts it instead of printing it into the run.
 */
public final class CapturedStderr implements AutoCloseable {

    private final PrintStream original = System.err;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private CapturedStderr() {
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    public static CapturedStderr start() {
        return new CapturedStderr();
    }

    /** Everything written so far, from any thread. */
    public String text() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Every line written so far, from any thread. */
    public List<String> lines() {
        return text().lines().toList();
    }

    /** Forget what was written so far. */
    public void reset() {
        captured.reset();
    }

    @Override
    public void close() {
        System.setErr(original);
    }
}
