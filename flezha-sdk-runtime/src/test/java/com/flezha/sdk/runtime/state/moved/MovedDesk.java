package com.flezha.sdk.runtime.state.moved;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;

/** The same tasklet as {@code KryoTypesTest.Desk}, after a class rename and a move to another package. */
public final class MovedDesk {

    private MovedDesk() {}

    @Tasklet.Spec(name = "desk")
    public static final class RenamedDesk implements Tasklet<String, RenamedDesk.Window, Void> {

        public static final class Window {
            public int count;
        }

        public record Seen(int n) implements Change<Window> {
            @Override
            public void applyTo(Window window) {
                window.count += n;
            }
        }

        @Override
        public void run(String input, Ctx<Window, Void> ctx) {}
    }
}
