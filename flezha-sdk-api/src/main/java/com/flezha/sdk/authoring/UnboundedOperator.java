package com.flezha.sdk.authoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a whole single-tasklet unbounded operator on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}: the short form of {@code docs/sdk/authoring_contract.md}
 * §5.
 *
 * <p>{@code runner.registerFactory(Echo.class)} reads it and declares an operator whose factory id
 * is {@link #id()}, wiring the entry channel {@link #input()} into the class and, when
 * {@link #sink()} is written, the class to that plugin sink. The tasklet is named {@link #id()}.
 * Nothing is generated and nothing registers itself.
 *
 * <p>More than one tasklet, or any class-level setting, is the full form: a class implementing
 * {@link com.flezha.sdk.operator.UnboundedOperator}, which this annotation's class must not also do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UnboundedOperator {

    /**
     * The factory id, which also names the tasklet. Required, and never derived from the class
     * name: the tasklet's name is part of every cell's state path, so a class rename must move
     * neither.
     */
    String id() default "";

    /** Plugin sink the tasklet's output is wired to. Empty (the default) declares no plugin channel. */
    String sink() default "";

    /** Name of the entry channel, the address a user injects on. Empty (the default) names it {@code input}. */
    String input() default "";

    /** The entry channel's overflow policy: {@code "drop_nothing"}, {@code "drop_newest"}, or {@code "drop_oldest"}. */
    String onFull() default "";
}
