package com.flezha.sdk.authoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a whole single-tasklet stream operative from a class with one public {@code run}
 * method: the short form of {@code docs/sdk/authoring_contract.md} §5. The {@code run} shapes are
 * {@link Tasklet}'s.
 *
 * <p>The annotation processor generates {@code <Class>Tasklet}, named {@link #id()}, and
 * {@code <Class>StreamOperative}, which wires the entry channel {@link #input()} into that tasklet
 * and, when {@link #sink()} is written, the tasklet to that plugin sink. The application
 * registers the class it wrote, {@code runner.registerFactory(Echo.class)}, which resolves the
 * generated operative; nothing registers itself.
 *
 * <p>More than one tasklet, or any class-level setting, is the full form: a class implementing
 * {@link com.flezha.sdk.operative.StreamOperative}, which this annotation's class must not also do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StreamOperative {

    /**
     * The factory id, which also names the generated tasklet. Required, and never derived from
     * the class name: an operator types it, and the tasklet's name is part of every cell's
     * state path, so a class rename must move neither.
     */
    String id() default "";

    /** Plugin sink the tasklet's output is wired to. Empty (the default) declares no plugin channel. */
    String sink() default "";

    /** Name of the entry channel, the address an operator injects on. Empty (the default) names it {@code input}. */
    String input() default "";

    /** As {@link Tasklet#key()}. */
    String key() default "";

    /** As {@link Tasklet#emits()}. */
    Class<?> emits() default void.class;

    /** As {@link Tasklet#keys()}. */
    String[] keys() default {};

    /** As {@link Tasklet#capacity()}. */
    int capacity() default 0;

    /** The entry channel's overflow policy: {@code "drop_nothing"}, {@code "drop_newest"}, or {@code "drop_oldest"}. */
    String onFull() default "";
}
