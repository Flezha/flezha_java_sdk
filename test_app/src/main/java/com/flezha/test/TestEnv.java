package com.flezha.test;

import com.flezha.sdk.operator.Operator;
import java.time.Duration;
import java.util.Optional;

/** Environment knobs the fixtures read, parsed the way the Rust test app parses them. */
final class TestEnv {

    private TestEnv() {}

    /** The variable as a long, or {@code fallback} when unset or unparseable. */
    static long longVar(String key, long fallback) {
        return optLong(key).orElse(fallback);
    }

    /** The variable as a long, or empty when unset or unparseable. */
    static Optional<Long> optLong(String key) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Where a sleeping fixture keeps its work: locally, sleeping after the class default unless the
     * drill or the bench driving it shortens the interval through
     * {@code FLEZHA_FIXTURE_SLEEP_AFTER_MS}.
     */
    static Operator.Durable fixtureDurable() {
        return new Operator.Durable.Local(optLong("FLEZHA_FIXTURE_SLEEP_AFTER_MS")
                .map(Duration::ofMillis)
                .orElse(Operator.DEFAULT_SLEEP_AFTER));
    }
}
