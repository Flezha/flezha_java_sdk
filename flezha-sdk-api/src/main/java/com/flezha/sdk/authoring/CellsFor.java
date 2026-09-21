package com.flezha.sdk.authoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the static-mode cell-routing method on a {@link Tasklet} class:
 * {@code List<String> name(Input input)}. The generated tasklet delegates
 * {@code cellsFor} to it, sets {@code cellsForOverridden()} to true, and
 * exposes the {@link Tasklet#keys()} as {@code cellKeys()}. Mutually exclusive
 * with edge keying (`.keyBy(f)` on the wiring).
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface CellsFor {}
