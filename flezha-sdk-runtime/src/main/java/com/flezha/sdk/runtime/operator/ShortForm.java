package com.flezha.sdk.runtime.operator;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EdgeStream;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.internal.ShortFormDeclaration;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * The short form, read at registration: a tasklet class annotated
 * {@link com.flezha.sdk.authoring.UnboundedOperator @UnboundedOperator} or
 * {@link com.flezha.sdk.authoring.BoundedOperator @BoundedOperator} becomes an operator whose
 * topology wires the entry channel into the class and, when a sink is written, the class to that
 * plugin sink. Every other operator method is at its interface default.
 */
final class ShortForm {

    private ShortForm() {}

    /** The operator {@code cls}'s short form {@code d} declares. */
    @Nonnull
    static Operator of(@Nonnull Class<?> cls, @Nonnull ShortFormDeclaration d) {
        TaskletModel model = refuse(cls, d);
        Wiring wiring = new Wiring(cls, model.inputType(), d);
        if (d.bounded()) {
            return new BoundedOperator() {
                @Override
                public String factoryId() {
                    return d.id();
                }

                @Override
                public LoopMode loopMode() {
                    return d.loopMode();
                }

                @Override
                public void channels(ChannelBuilder c) {
                    wiring.declare(c);
                }
            };
        }
        return new UnboundedOperator() {
            @Override
            public String factoryId() {
                return d.id();
            }

            @Override
            public void channels(ChannelBuilder c) {
                wiring.declare(c);
            }
        };
    }

    /** The class's model, once every short-form refusal has passed. */
    private static TaskletModel refuse(Class<?> cls, ShortFormDeclaration d) {
        if (!Tasklet.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException(
                    cls.getName() + " is annotated " + d.annotation() + " but does not implement Tasklet");
        }
        if (Operator.class.isAssignableFrom(cls)) {
            throw new IllegalArgumentException("ShortFormWithTopology: " + d.annotation() + " makes "
                    + cls.getName() + " a whole operator, and it also implements an operator interface;"
                    + " keep one of the two");
        }
        if (d.id().isEmpty()) {
            throw new IllegalArgumentException(d.annotation() + " on " + cls.getName() + " requires `id`: an"
                    + " operator's factory id is an address a user types, never derived from the class name");
        }
        Tasklet.Spec spec = cls.getAnnotation(Tasklet.Spec.class);
        if (spec != null && !spec.name().equals(d.id())) {
            throw new IllegalArgumentException("ShortFormNameMismatch: " + cls.getName() + " declares operator id `"
                    + d.id() + "` and @Tasklet.Spec name `" + spec.name() + "`; a short form's tasklet is"
                    + " named after its id, so the two must be equal");
        }
        TaskletModel model = TaskletModel.read(cls, null);
        if (!d.sink().isEmpty() && model.typesResolved() && model.outputType() == Void.class) {
            throw new IllegalArgumentException("SinkWithoutOutput: " + cls.getName() + " declares sink `" + d.sink()
                    + "`, but its Output is Void; a sink needs an output to feed the plugin");
        }
        if (!d.onFull().isEmpty() && overflow(d.onFull()) == null) {
            throw new IllegalArgumentException(d.annotation() + " on " + cls.getName() + ": onFull must be one of"
                    + " drop_nothing, drop_newest, drop_oldest");
        }
        return model;
    }

    private static OverflowPolicy overflow(String onFull) {
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            if (policy.name().equals(onFull.toUpperCase(Locale.ROOT))) {
                return policy;
            }
        }
        return null;
    }

    /** The one-tasklet topology: the entry channel into the class, and the class to its sink. */
    private record Wiring(Class<?> cls, Class<?> inputType, ShortFormDeclaration d) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        void declare(ChannelBuilder c) {
            EdgeStream entry = c.input(inputType, d.input().isEmpty() ? "input" : d.input());
            if (!d.onFull().isEmpty()) {
                entry = entry.onFull(overflow(d.onFull()));
            }
            Stream out = entry.into((Class) cls);
            if (!d.sink().isEmpty()) {
                out.sink(d.sink());
            }
        }
    }
}
