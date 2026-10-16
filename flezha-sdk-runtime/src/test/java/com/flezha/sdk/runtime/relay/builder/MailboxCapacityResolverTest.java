package com.flezha.sdk.runtime.relay.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.CapturedStderr;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Java mirror of the Rust SDK's {@code resolve_mailbox_capacity_inner}
 * test cases ({@code sdk/rust/src/relay/builder.rs}). The resolver
 * RESOLVES and nothing else: an out-of-range value is written into the record
 * as resolved, and {@code flz_core} names it — those cases live in
 * {@code BoundedMailboxTopologyTest}.
 */
class MailboxCapacityResolverTest {

    @Test
    @DisplayName("explicit override on the Tasklet wins over env value")
    void overrideOnTaskletTakesPrecedence() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.of(4096), "2048"))
                .isEqualTo(4096);
    }

    @Test
    @DisplayName("env value is used when the Tasklet does not override")
    void envWinsOverDefault() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.empty(), "2048"))
                .isEqualTo(2048);
    }

    @Test
    @DisplayName("unparseable env value falls through to the default")
    void unparseableEnvFallsThroughToDefault() {
        try (CapturedStderr stderr = CapturedStderr.start()) {
            assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.empty(), "banana"))
                    .isEqualTo(MailboxCapacityResolver.DEFAULT_MAILBOX_CAPACITY);
            assertThat(stderr.lines())
                    .containsExactly("[flezha] Invalid FLEZHA_MAILBOX_CAPACITY=banana; falling back to "
                            + MailboxCapacityResolver.DEFAULT_MAILBOX_CAPACITY);
        }
    }

    @Test
    @DisplayName("missing env value falls through to the default")
    void missingEnvFallsThroughToDefault() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.empty(), null))
                .isEqualTo(MailboxCapacityResolver.DEFAULT_MAILBOX_CAPACITY);
    }

    @Test
    @DisplayName("a zero declared override is resolved as declared, for the core to judge")
    void zeroDeclaredIsResolvedNotRefused() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.of(0), null))
                .isZero();
    }

    @Test
    @DisplayName("a declared override above the upper bound is resolved as declared")
    void tooLargeIsResolvedNotRefused() {
        assertThat(MailboxCapacityResolver.resolveInner(
                        OptionalInt.of(MailboxCapacityResolver.MAX_MAILBOX_CAPACITY + 1), null))
                .isEqualTo(MailboxCapacityResolver.MAX_MAILBOX_CAPACITY + 1);
    }

    @Test
    @DisplayName("zero declared override beats a valid env value (override always wins)")
    void zeroOverrideBeatsEnv() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.of(0), "4096"))
                .isZero();
    }

    @Test
    @DisplayName("capacity = 1 is allowed")
    void capacityOneIsAllowed() {
        assertThat(MailboxCapacityResolver.resolveInner(OptionalInt.of(1), null))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("capacity = MAX_MAILBOX_CAPACITY is allowed")
    void maxIsAllowed() {
        assertThat(MailboxCapacityResolver.resolveInner(
                        OptionalInt.of(MailboxCapacityResolver.MAX_MAILBOX_CAPACITY), null))
                .isEqualTo(MailboxCapacityResolver.MAX_MAILBOX_CAPACITY);
    }
}
