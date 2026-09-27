package com.flezha.sdk.internal;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.authoring.UnboundedOperator;
import com.flezha.sdk.operator.LoopMode;
import javax.annotation.Nullable;

/**
 * What a short-form annotation on a class states, whichever of the two it is: the one reading of
 * {@code @UnboundedOperator} / {@code @BoundedOperator} the runner and the runtime share.
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls this.
 *
 * @param annotation the annotation's spelling, for refusals
 * @param bounded whether it declares a bounded operator
 */
public record ShortFormDeclaration(
        String annotation, boolean bounded, String id, String sink, String input, String onFull, LoopMode loopMode) {

    /** The short form {@code cls} declares, or {@code null} when it carries neither annotation. */
    @Nullable
    public static ShortFormDeclaration of(Class<?> cls) {
        UnboundedOperator unbounded = cls.getAnnotation(UnboundedOperator.class);
        if (unbounded != null) {
            return new ShortFormDeclaration(
                    "@UnboundedOperator",
                    false,
                    unbounded.id(),
                    unbounded.sink(),
                    unbounded.input(),
                    unbounded.onFull(),
                    LoopMode.ONCE);
        }
        BoundedOperator bounded = cls.getAnnotation(BoundedOperator.class);
        return bounded == null
                ? null
                : new ShortFormDeclaration(
                        "@BoundedOperator",
                        true,
                        bounded.id(),
                        bounded.sink(),
                        bounded.input(),
                        bounded.onFull(),
                        bounded.loopMode());
    }
}
