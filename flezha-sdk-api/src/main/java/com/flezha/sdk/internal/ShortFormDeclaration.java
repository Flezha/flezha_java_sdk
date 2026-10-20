package com.flezha.sdk.internal;

import com.flezha.sdk.authoring.ShortRelay;
import javax.annotation.Nullable;

/**
 * What a short-form annotation on a class states: the one reading of {@code @ShortRelay} the runner
 * and the runtime share.
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls this.
 *
 * @param settings the annotation itself, whose nested settings the runtime reads as the tree
 */
public record ShortFormDeclaration(String factoryId, String output, String input, String onFull, ShortRelay settings) {

    /** The annotation's spelling, for refusals. */
    public static final String ANNOTATION = "@ShortRelay";

    /** The short form {@code cls} declares, or {@code null} when it carries no annotation. */
    @Nullable
    public static ShortFormDeclaration of(Class<?> cls) {
        ShortRelay declared = cls.getAnnotation(ShortRelay.class);
        return declared == null
                ? null
                : new ShortFormDeclaration(
                        declared.factoryId(), declared.output(), declared.input(), declared.onFull(), declared);
    }
}
