/**
 * The short form: a whole single-tasklet operator declared on the tasklet class itself.
 *
 * <p>{@link com.flezha.sdk.authoring.Operator} goes on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}. It is read when the class is registered,
 * {@code runner.registerFactory(Echo.class)}, which declares an operator wiring an entry channel
 * into the class and, when a sink is written, the class to that plugin sink. It takes the settings
 * tree the full form takes. Nothing is generated. The tasklet takes the operator's {@code id} as
 * its name, so a short form carries {@code @Tasklet.Spec} only for its settings.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.authoring;
