package com.flezha.sdk.runtime.operative.builder;

import com.flezha.sdk.runtime.config.FlezhaEnv;
import java.util.OptionalInt;

/**
 * Resolves a Tasklet's per-Cell mailbox capacity per the Rust SDK's
 * three-tier precedence rule:
 *
 * <ol>
 *   <li>The Tasklet's own {@code mailboxCapacity()}, when it overrides it.</li>
 *   <li>{@code FLEZHA_MAILBOX_CAPACITY} env var (parsed as a positive int).</li>
 *   <li>Built-in default ({@link #DEFAULT_MAILBOX_CAPACITY}).</li>
 * </ol>
 *
 * <p>The resolved value is written into the record as declared — an
 * out-of-range one included. {@code flz_core} names it
 * ({@code MailboxCapacityZero}, {@code MailboxCapacityTooLarge}), so
 * resolving and judging stay apart. Unparseable env values fall through to
 * the default — runners tolerate typos in env values during development.
 * Split into a pure-function core so operative tests don't have to mutate
 * the process-global env var.
 */
public final class MailboxCapacityResolver {

    public static final int DEFAULT_MAILBOX_CAPACITY = 1024;
    public static final int MAX_MAILBOX_CAPACITY = 1_048_576;
    public static final String ENV_MAILBOX_CAPACITY = "FLEZHA_MAILBOX_CAPACITY";

    private MailboxCapacityResolver() {}

    /** Resolve using the live process environment for the env-var tier. */
    public static int resolve(OptionalInt declared) {
        return resolveInner(declared, System.getenv(ENV_MAILBOX_CAPACITY));
    }

    /**
     * Pure-function core. {@code envValue} may be {@code null} (unset) or
     * a string that fails to parse as a positive {@code int}, in which case
     * the default is used.
     */
    static int resolveInner(OptionalInt declared, String envValue) {
        return declared.orElseGet(
                () -> FlezhaEnv.parsePositive(ENV_MAILBOX_CAPACITY, envValue, DEFAULT_MAILBOX_CAPACITY));
    }
}
