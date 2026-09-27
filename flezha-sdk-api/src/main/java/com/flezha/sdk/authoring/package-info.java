/**
 * The short form: a whole single-tasklet operator declared on the tasklet class itself.
 *
 * <p>{@link com.flezha.sdk.authoring.UnboundedOperator} and
 * {@link com.flezha.sdk.authoring.BoundedOperator} go on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}. They are read when the class is registered,
 * {@code runner.registerFactory(Echo.class)}, which declares an operator of that kind wiring an
 * entry channel into the class and, when a sink is written, the class to that plugin sink.
 * Nothing is generated. The tasklet takes the operator's {@code id} as its name, so a short form
 * carries {@code @Tasklet.Spec} only for its settings.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.authoring;
