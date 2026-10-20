package com.flezha.sdk.authoring;

import com.flezha.sdk.relay.Relay.BeyondWindow;
import com.flezha.sdk.relay.Relay.Each;
import com.flezha.sdk.relay.Relay.OnFailure;
import com.flezha.sdk.relay.Relay.Outputs;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a whole single-tasklet relay on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}: the short form of {@code docs/internal/sdk/authoring_contract.md}
 * §5.
 *
 * <p>{@code runner.registerFactory(Echo.class)} reads it and declares a relay whose factory id
 * is {@link #factoryId()}, wiring the entry channel {@link #input()} into the class and, when
 * {@link #output()} is written, the class to that plugin output. The tasklet is named {@link #factoryId()}.
 * Nothing is generated and nothing registers itself.
 *
 * <p>It takes the class settings the full form ({@link com.flezha.sdk.relay.Relay}) takes,
 * written as nested annotations. Each setting is a member of its parent only, so a setting under
 * the wrong parent does not compile; a "one of" is an array holding at most one branch, and two
 * branches written at once are refused at registration as {@code topology.invalid_setting}:
 *
 * <pre>{@code
 * @ShortRelay(factoryId = "chat", run = @Run(steps = @Steps(each = Each.PER_INPUT)),
 *         durable = @Durable(local = @Level(sleepAfterMs = 2_000)))
 * }</pre>
 *
 * <p>More than one tasklet is the full form, a class implementing
 * {@link com.flezha.sdk.relay.Relay}, which this annotation's class must not also do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ShortRelay {

    /**
     * The factory id, which also names the tasklet. Required, and never derived from the class
     * name: the tasklet's name is part of every cell's state path, so a class rename must move
     * neither.
     */
    String factoryId() default "";

    /** Plugin output the tasklet's output is wired to. Empty (the default) declares no plugin channel. */
    String output() default "";

    /** Name of the entry channel, the address a user injects on. Empty (the default) names it {@code input}. */
    String input() default "";

    /** The entry channel's overflow policy: {@code "drop_nothing"}, {@code "drop_newest"}, or {@code "drop_oldest"}. */
    String onFull() default "";

    /** As {@link com.flezha.sdk.relay.Relay#description()}. */
    String description() default "";

    /** As {@link com.flezha.sdk.relay.Relay#maxDuration()}, in milliseconds; {@link #NONE} is none. */
    long maxDurationMs() default NONE;

    /** As {@link com.flezha.sdk.relay.Relay#run()}. */
    Run run() default @Run;

    /** As {@link com.flezha.sdk.relay.Relay#durable()}. */
    Durable durable() default @Durable;

    /** The unwritten value of a numeric setting. */
    long NONE = -1;

    /** {@code flow} (the default when neither is written) or {@code steps}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Run {
        Flow[] flow() default {};

        Steps[] steps() default {};
    }

    /** A flow; an empty {@link #effectivelyOnce()} delivers at least once. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Flow {
        EffectivelyOnce[] effectivelyOnce() default {};
    }

    /** The effectively-once tier; an empty {@link #checkpoint()} acknowledges per input. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface EffectivelyOnce {
        Checkpoint[] checkpoint() default {};

        long correctionMs() default NONE;

        BeyondWindow beyondWindow() default BeyondWindow.PROCESS_AS_ARRIVAL;
    }

    /** Checkpoint acknowledgement. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Checkpoint {
        long every() default NONE;

        long maxDelayMs() default NONE;

        Outputs outputs() default Outputs.AS_PRODUCED;
    }

    /** A steps run. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Steps {
        Each each() default Each.ONCE;

        OnFailure onFailure() default OnFailure.UNDO_ALL;
    }

    /**
     * {@code @Durable(false)} keeps nothing; otherwise {@link #local()} (the default when nothing
     * is written) or {@link #remote()}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Durable {
        boolean value() default true;

        Level[] local() default {};

        Level[] remote() default {};
    }

    /** A durable level's members. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Level {
        /**
         * Milliseconds idle before an instance sleeps; {@link #NEVER} never sleeps, and {@link
         * #RUN_DEFAULT} (the default) sleeps as the run does: a flow instance never, a steps
         * instance after six hours.
         */
        long sleepAfterMs() default RUN_DEFAULT;

        long NEVER = -1;

        long RUN_DEFAULT = -2;
    }
}
