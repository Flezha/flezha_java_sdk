package com.flezha.sdk.native_;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JNI smoke tests for the poll-core bridge. Tagged {@code jni} so they only
 * run when the host-triple {@code flz_core_ffi} cdylib has been staged into
 * {@code flezha-sdk-native/src/main/resources/native/}.
 *
 * <p>These tests do NOT require a live controller.
 */
@Tag("jni")
class FlzCoreJniTest {

    @Test
    @DisplayName("native core speaks the shim's schema version")
    void schemaVersionMatches() {
        assertThat(FlzCore.schemaVersion()).isEqualTo(FlzCore.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("context create → free round-trip")
    void contextLifecycle() {
        long ctx = FlzCore.newContext();
        assertThat(ctx).isNotZero();
        FlzCore.contextFree(ctx);
    }

    @Test
    @DisplayName("Version mismatch refuses at context creation")
    void versionMismatchThrows() {
        assertThatThrownBy(() -> FlzCore.contextNew(999)).isInstanceOfSatisfying(FlezhaException.class, e -> {
            assertThat(e.code()).isEqualTo("runtime.schema_mismatch");
            assertThat(e.details()).containsEntry("shim", 999).containsEntry("core", FlzCore.SCHEMA_VERSION);
        });
    }

    @Test
    @DisplayName("a value the binding cannot map is refused as uncatalogued, naming why")
    void anUnmappableArgumentIsUncatalogued() {
        long ctx = FlzCore.newContext();
        try {
            assertThatThrownBy(() -> FlzCore.localOnSink(ctx, "a\u0000b"))
                    .isInstanceOfSatisfying(FlezhaException.class, e -> {
                        assertThat(e.code()).isEqualTo("internal.uncatalogued");
                        assertThat(e.details()).containsKey("reason");
                    });
        } finally {
            FlzCore.contextFree(ctx);
        }
    }

    @Test
    @DisplayName("the monotonic clock advances and never goes backwards")
    void monotonicClockAdvances() {
        long first = FlzCore.monotonicNanos();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long later = first;
        while (later == first) {
            assertThat(System.nanoTime())
                    .as("the monotonic clock never advanced")
                    .isLessThan(deadline);
            later = FlzCore.monotonicNanos();
            assertThat(later).as("the monotonic clock went backwards").isGreaterThanOrEqualTo(first);
        }
        assertThat(later).isGreaterThan(first);
    }
}
