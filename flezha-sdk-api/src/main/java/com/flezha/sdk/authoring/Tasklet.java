package com.flezha.sdk.authoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an authoring class whose {@code run} method is expanded into a full
 * {@link com.flezha.sdk.tasklet.Tasklet} implementation by the Flezha annotation
 * processor. The annotation processor generates {@code <ClassName>Tasklet}
 * implementing the trait surface, inferring the associated types from the
 * {@code run} signature per {@code docs/sdk/authoring_contract.md} §2.
 *
 * <p>Supported {@code run} shapes:
 * <ul>
 *   <li>{@code Output run(Input input)} — stateless.</li>
 *   <li>{@code Output run(Input input, <State>Handle state)}: stateful, taking
 *       the handle a {@link State} class generates.</li>
 *   <li>{@code Output run(Input input, StateHandle<State, Change> state)} —
 *       stateful; {@code State}/{@code Change} are read from the handle's type
 *       arguments.</li>
 *   <li>A trailing {@code TaskletCtx ctx} parameter with a {@code void}
 *       return — the body performs any number of emits itself and
 *       {@link #emits()} declares the output type.</li>
 *   <li>An {@code Optional<Output>} return emits only when a value is present.</li>
 *   <li>A {@code void} return declares no output channel.</li>
 * </ul>
 *
 * <p>The annotation declares a tasklet and nothing else. A class whose one tasklet is a whole
 * operative is annotated {@link StreamOperative} or {@link WorkflowOperative} instead, which take
 * the same {@code run} shapes.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Tasklet {

    /** Stable name, which saved state is filed under. Defaults to the snake_case class name. */
    String id() default "";

    /**
     * Name of a {@code static String <name>(Output out)} method on this class supplying the
     * emit's natural key. Set it to feed a {@code .keyed()} channel: the generated run then
     * calls {@code ctx.emitKeyed(...)}, the only emit such a channel accepts. Requires a
     * non-void return type.
     */
    String key() default "";

    /**
     * Output type of a {@code run} that takes a trailing {@code TaskletCtx}.
     * That shape returns {@code void} — the body emits — so the return type has
     * no output left to declare and this does it instead. {@code void.class}
     * (the default) means the tasklet declares no output channel; setting it
     * without a context parameter is an error.
     */
    Class<?> emits() default void.class;

    /** Static-mode cell keys. Requires a {@link CellsFor} method. */
    String[] keys() default {};

    /** Per-cell mailbox capacity override. {@code 0} keeps the default. */
    int capacity() default 0;
}
