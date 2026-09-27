package com.flezha.sdk.operator;

import java.util.List;
import java.util.Objects;

/**
 * Thrown by {@link com.flezha.sdk.FlezhaRunner#registerFactory(Class)} when a
 * UnboundedOperator's declared topology is refused. The verdict it carries is
 * the core's, rendered once by {@code flz_core::host::issue::render_issues}:
 *
 * <pre>
 * topology rejected [DuplicateKeyBy, DanglingTransformChain]:
 *   - channel `in` declares .key_by(...) twice before one `.into(...)`
 *   - channel `in` declares a transform or .key_by(...) that is never closed …
 * </pre>
 *
 * <p>The names in the bracketed list are the reference vocabulary every SDK
 * reports a refusal under; the core hands them out as data beside the rendered
 * verdict, and {@link #refusals()} answers them so a caller can branch on a
 * name instead of on a sentence.
 */
public class UnboundedOperatorConfigurationException extends RuntimeException {

    private final List<String> refusals;

    /** A refusal the core has no vocabulary for, so it lists no names. */
    public UnboundedOperatorConfigurationException(String verdict) {
        this(verdict, List.of());
    }

    public UnboundedOperatorConfigurationException(String verdict, List<String> refusals) {
        super(Objects.requireNonNull(verdict));
        this.refusals = List.copyOf(refusals);
    }

    /**
     * The refusal names the verdict lists, in the order the core rendered
     * them. Empty when the refusal is one the core has no name for.
     */
    public List<String> refusals() {
        return refusals;
    }

    /**
     * Raised when a tasklet's type argument cannot be recovered from its generic supertype chain:
     * the argument is still generic at the concrete class. Naming the class and the parameter is
     * the whole contract: a guess would surface later as a type mismatch far from its cause.
     *
     * <p>One of the refusals the core has no vocabulary for: erasure is a Java fact and the record
     * carries no type tokens, so nothing the builder could submit would let the core answer this.
     */
    public static UnboundedOperatorConfigurationException unresolvedTypeToken(String className, String parameter) {
        return new UnboundedOperatorConfigurationException("tasklet class `" + className + "` leaves the `"
                + parameter + "` type argument of Tasklet<Input, State, Output> unresolved; declare it"
                + " concretely on the implements/extends clause");
    }

    /**
     * Raised when a tasklet class named in the wiring carries no {@code @Tasklet.Spec}. The name
     * it declares is where the tasklet's state is saved, so it is never derived from the class.
     */
    public static UnboundedOperatorConfigurationException taskletSpecMissing(String className) {
        return new UnboundedOperatorConfigurationException(
                "TaskletSpecMissing: tasklet class `" + className + "` carries no @Tasklet.Spec(name = ...);"
                        + " the name it declares is where the tasklet's state is saved",
                List.of("TaskletSpecMissing"));
    }
}
