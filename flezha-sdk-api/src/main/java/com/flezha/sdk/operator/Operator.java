package com.flezha.sdk.operator;

import com.flezha.sdk.channel.ChannelBuilder;
import javax.annotation.Nonnull;

/**
 * The shared base of both operator kinds.
 *
 * <p>Both kinds declare a graph of tasklets on the same {@link ChannelBuilder}, in
 * {@link #channels(ChannelBuilder)}. {@link UnboundedOperator} runs that graph continuously over a
 * stream, holding per-cell state. {@link BoundedOperator} runs it to completion, commits it stage
 * by stage, and resumes at the stage it reached. Both are operators, both are declared on a
 * runner, and both are scheduled, restarted and relocated by the controller in the same way.
 *
 * <p>The members common to both are {@link #factoryId()} and {@link #channels(ChannelBuilder)}.
 * Everything that differs between the kinds, the persistence opt-in on one side and the bounded operator
 * settings on the other, stays on the per-kind interface.
 *
 * <p>You rarely implement this interface directly. Implement one of the two kinds, which extend it:
 *
 * <pre>{@code
 * public final class WordCount implements UnboundedOperator { ... }    // continuous, stateful
 * public final class Checkout implements BoundedOperator { ... }   // runs to completion, resumable
 * }</pre>
 *
 * <p>Both are declared on the same runner, through the same call, which reads the kind from the
 * interface the class implements:
 *
 * <pre>{@code
 * Flezha.createRunner("my_app")
 *         .registerFactory(WordCount.class)
 *         .registerFactory(Checkout.class)
 *         .run();
 * }</pre>
 *
 * <p>Adding a further operator kind means extending this interface and teaching
 * {@link com.flezha.sdk.FlezhaRunner#registerFactory(Class)} to read it, without modifying either
 * existing authoring surface.
 */
public interface Operator {

    /**
     * The stable, controller-facing identity of this operator class, and the name
     * {@code flz operator start <factory_id>} resolves. Exactly one factory id per class.
     *
     * <p>This value is written into durable state and into the controller's registry, so changing
     * it does not rename an existing operator: it declares a new one, and state recorded under the
     * previous identity is no longer associated with it. Choose it deliberately at authoring time.
     */
    @Nonnull
    String factoryId();

    /**
     * Declares the graph. Called exactly once per class, at
     * {@link com.flezha.sdk.FlezhaRunner#registerFactory(Class) registerFactory} time, and the resulting
     * graph is shared by every instance of this operator that the controller later starts.
     *
     * <p>Channels are never constructed directly. Each call on the builder returns the next handle
     * in the chain, so the whole graph is one fluent expression, and the completed declaration is
     * validated as a whole before any message is processed. An inconsistent declaration, or one
     * requesting a capability the deployment cannot supply, is refused at registration naming the
     * declaration at fault.
     *
     * <p>Because this method runs once at registration rather than per message, it must not depend
     * on anything that varies at run time.
     */
    void channels(ChannelBuilder c);
}
