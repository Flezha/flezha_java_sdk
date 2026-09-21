package com.flezha.sdk.authoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the per-cell state-init method on a {@link Tasklet} class:
 * {@code State name(java.util.Optional<String> cellKey)}. The generated tasklet
 * delegates {@code initState} to it, passing the cell's key (absent
 * in single-cell mode and the test-harness default), instead of returning
 * {@code new State()}.
 *
 * <p>Use it when a cell's starting state is a function of its key — e.g. a
 * static-mode tasklet whose window length or bucket span is encoded in
 * the cell key. Requires a stateful {@code run} (a {@code @State} handle
 * parameter); declaring it on a stateless tasklet is a compile-time error. The
 * restore payload stays runtime-applied after {@code initState} returns, so the
 * method never sees it. Mirrors Rust's {@code #[flezha::tasklet(init = fn)]}.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface InitState {}
