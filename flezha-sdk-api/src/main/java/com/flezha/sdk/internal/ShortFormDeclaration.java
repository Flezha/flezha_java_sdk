package com.flezha.sdk.internal;

import com.flezha.sdk.authoring.Operator;
import javax.annotation.Nullable;

/**
 * What a short-form annotation on a class states: the one reading of {@code @Operator} the runner
 * and the runtime share.
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls this.
 *
 * @param settings the annotation itself, whose nested settings the runtime reads as the tree
 */
public record ShortFormDeclaration(String id, String sink, String input, String onFull, Operator settings) {

    /** The annotation's spelling, for refusals. */
    public static final String ANNOTATION = "@Operator";

    /** The short form {@code cls} declares, or {@code null} when it carries no annotation. */
    @Nullable
    public static ShortFormDeclaration of(Class<?> cls) {
        Operator declared = cls.getAnnotation(Operator.class);
        return declared == null
                ? null
                : new ShortFormDeclaration(
                        declared.id(), declared.sink(), declared.input(), declared.onFull(), declared);
    }
}
