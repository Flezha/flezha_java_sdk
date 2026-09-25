package com.flezha.sdk.runtime.operator.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.operator.StatelessTestTasklet;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.Hook;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Writing an optional hook is what declares it: the builder reads each one off the class. */
class HookDeclarationTest {

    @Test
    @DisplayName("a hook written with the concrete input type is declared, through the compiler's bridge")
    void concreteParameterTypeIsDeclared() {
        assertThat(Hook.CELLS_FOR.declaredBy(ConcreteInput.class)).isTrue();
        assertThat(Hook.EVENT_TIME.declaredBy(ConcreteInput.class)).isTrue();
    }

    @Test
    @DisplayName("a hook written with the erased signature is declared")
    void erasedSignatureIsDeclared() {
        assertThat(Hook.CELLS_FOR.declaredBy(ObjectInput.class)).isTrue();
        assertThat(Hook.COMPENSATE.declaredBy(ObjectInput.class)).isTrue();
        assertThat(Hook.MAILBOX_CAPACITY.declaredBy(ObjectInput.class)).isTrue();
        assertThat(Hook.MAX_CELLS.declaredBy(ObjectInput.class)).isTrue();
    }

    @Test
    @DisplayName("a hook written on an intermediate class is declared for its subclass")
    void intermediateClassIsDeclared() {
        assertThat(Hook.COMPENSATE.declaredBy(Reserve.class)).isTrue();
        assertThat(Hook.CELLS_FOR.declaredBy(Reserve.class)).isFalse();
    }

    @Test
    @DisplayName("an untouched interface default is not declared")
    void interfaceDefaultIsNotDeclared() {
        for (Hook hook : Hook.values()) {
            assertThat(hook.declaredBy(Bare.class)).as(hook.name()).isFalse();
        }
    }

    public static class Bare extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "bare";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    public static final class ConcreteInput extends Bare {
        @Override
        public List<String> cellsFor(String input) {
            return List.of(input);
        }

        @Override
        public long eventTime(String input) {
            return 0L;
        }
    }

    public static final class ObjectInput extends StatelessTestTasklet<Object, Object, Object> {
        @Override
        public String name() {
            return "object_input";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(Object input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}

        @Override
        public List<String> cellsFor(Object input) {
            return List.of();
        }

        @Override
        public void compensate(List<Object> completedOutput, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}

        @Override
        public int mailboxCapacity() {
            return 64;
        }

        @Override
        public long maxCells() {
            return 10L;
        }
    }

    public abstract static class Step extends Bare {
        @Override
        public void compensate(List<String> completedOutput, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class Reserve extends Step {}
}
