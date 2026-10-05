/**
 * The short form: a whole single-tasklet relay declared on the tasklet class itself.
 *
 * <p>{@link com.flezha.sdk.authoring.Relay} goes on a class that implements
 * {@link com.flezha.sdk.tasklet.Tasklet}. It is read when the class is registered,
 * {@code runner.registerFactory(Echo.class)}, which declares a relay wiring an entry channel
 * into the class and, when an output is written, the class to that plugin output. It takes the settings
 * tree the full form takes. Nothing is generated. The tasklet takes the relay's {@code id} as
 * its name, so a short form carries {@code @Tasklet.Spec} only for its settings.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.authoring;
