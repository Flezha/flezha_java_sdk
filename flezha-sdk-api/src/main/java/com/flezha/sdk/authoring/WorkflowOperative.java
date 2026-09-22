package com.flezha.sdk.authoring;

import com.flezha.sdk.operative.LoopMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a whole single-tasklet workflow operative from a class with one public {@code run}
 * method: the short form of {@code docs/sdk/authoring_contract.md} §5, a {@code once} workflow that
 * runs on its first message unless {@link #loopMode()} says otherwise. The {@code run} shapes are
 * {@link Tasklet}'s.
 *
 * <p>The annotation processor generates {@code <Class>Tasklet}, named {@link #id()}, and
 * {@code <Class>WorkflowOperative}, which wires the entry channel {@link #input()} into that
 * tasklet and, when {@link #sink()} is written, the tasklet to that plugin sink. The
 * application registers the class it wrote, {@code runner.registerFactory(Place.class)}, which
 * resolves the generated operative; nothing registers itself.
 *
 * <p>More than one tasklet, or any class-level setting other than the loop mode, is the full form:
 * a class implementing {@link com.flezha.sdk.operative.WorkflowOperative}, which this annotation's
 * class must not also do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WorkflowOperative {

    /** As {@link StreamOperative#id()}: required, and never derived from the class name. */
    String id() default "";

    /** As {@link StreamOperative#sink()}. */
    String sink() default "";

    /** As {@link StreamOperative#input()}. */
    String input() default "";

    /** As {@link Tasklet#key()}. */
    String key() default "";

    /** As {@link Tasklet#emits()}. */
    Class<?> emits() default void.class;

    /** As {@link Tasklet#keys()}. */
    String[] keys() default {};

    /** As {@link Tasklet#capacity()}. */
    int capacity() default 0;

    /** As {@link StreamOperative#onFull()}. */
    String onFull() default "";

    /**
     * How many times the generated operative runs its tasklet, returned from its
     * {@link com.flezha.sdk.operative.WorkflowOperative#loopMode()}: one pass by default, or
     * {@link LoopMode#PER_INPUT} for one run per message.
     */
    LoopMode loopMode() default LoopMode.ONCE;
}
