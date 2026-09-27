package com.flezha.sdk.authoring;

import com.flezha.sdk.operator.LoopMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a whole single-tasklet bounded operator on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}: the short form of {@code docs/sdk/authoring_contract.md}
 * §5, a {@code once} bounded operator that runs on its first message unless {@link #loopMode()}
 * says otherwise.
 *
 * <p>{@code runner.registerFactory(Place.class)} reads it and declares an operator whose factory id
 * is {@link #id()}, wiring the entry channel {@link #input()} into the class and, when
 * {@link #sink()} is written, the class to that plugin sink. The tasklet is named {@link #id()}.
 * Nothing is generated and nothing registers itself.
 *
 * <p>More than one tasklet, or any class-level setting other than the loop mode, is the full form:
 * a class implementing {@link com.flezha.sdk.operator.BoundedOperator}, which this annotation's
 * class must not also do.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BoundedOperator {

    /** As {@link UnboundedOperator#id()}: required, and never derived from the class name. */
    String id() default "";

    /** As {@link UnboundedOperator#sink()}. */
    String sink() default "";

    /** As {@link UnboundedOperator#input()}. */
    String input() default "";

    /** As {@link UnboundedOperator#onFull()}. */
    String onFull() default "";

    /**
     * How many times the operator runs its tasklet, returned from its
     * {@link com.flezha.sdk.operator.BoundedOperator#loopMode()}: one pass by default, or
     * {@link LoopMode#PER_INPUT} for one run per message.
     */
    LoopMode loopMode() default LoopMode.ONCE;
}
