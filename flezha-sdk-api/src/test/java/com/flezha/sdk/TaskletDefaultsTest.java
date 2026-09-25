package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TaskletDefaultsTest {

    @Test
    void compactAfterBytesDefaultsTo8Mib() {
        StatelessTasklet tasklet = new StatelessTasklet();
        // 8 MiB, in lockstep with Rust and the controller's default.
        assertThat(tasklet.compactAfterBytes()).isEqualTo(8388608L);
    }

    @Test
    void schemaVersionDefaultsToOne() {
        StatelessTasklet tasklet = new StatelessTasklet();
        assertThat(tasklet.schemaVersion()).isEqualTo(1);
    }

    @Test
    void kryoTypesDefaultsToEmpty() {
        StatelessTasklet tasklet = new StatelessTasklet();
        assertThat(tasklet.kryoTypes()).isEmpty();
    }

    @Test
    void fromConfigVoidInvokesNoArgCtor() {
        StatelessTasklet tasklet = new StatelessTasklet();
        Tasklet<String, String, String, NoChange, WallClock, Void> fresh = tasklet.fromConfig(null);
        assertThat(fresh).isNotNull();
        assertThat(fresh).isNotSameAs(tasklet);
        assertThat(fresh.name()).isEqualTo("stateless");
    }

    @Test
    void fromConfigVoidSurfacesAThrowingConstructorAsTheAuthorsError() {
        RefusingTasklet tasklet = new RefusingTasklet();
        RefusingTasklet.REFUSE.set(true);
        try {
            assertThatThrownBy(() -> tasklet.fromConfig(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(RefusingTasklet.REFUSAL);
        } finally {
            RefusingTasklet.REFUSE.set(false);
        }
    }

    @Test
    void applyChangeWithNoChangeIsNoop() {
        StatelessTasklet tasklet = new StatelessTasklet();
        // Cannot construct NoChange (uninhabited); just confirm the call path
        // returns without throwing for the NoChange-typed Tasklet. We prove the
        // path with a null cast accepted by the type system because NoChange
        // is uninhabited.
        tasklet.applyChange("state", null);
    }

    @Test
    void applyChangeWithUserChangeWithoutOverrideThrows() {
        DeclaredTokens tasklet = new DeclaredTokens();
        assertThatThrownBy(() -> tasklet.applyChange(0L, new SampleChange(1)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not override applyChange");
    }

    /**
     * fails when: the reflective resolution stops reading the implements
     * clause — every hand-written Tasklet would then be back to five Class
     * literals restating what the clause already says.
     */
    @Test
    void tokensResolveFromTheImplementsClause() {
        DeclaredTokens tasklet = new DeclaredTokens();
        assertThat(tasklet.inputType()).isEqualTo(String.class);
        assertThat(tasklet.outputType()).isEqualTo(Integer.class);
        assertThat(tasklet.changeType()).isEqualTo(SampleChange.class);
        assertThat(tasklet.clockType()).isEqualTo(WallClock.class);
        assertThat(tasklet.configType()).isEqualTo(Void.class);
    }

    /**
     * fails when: resolution stops substituting type variables across the
     * supertype chain — a generic base class holding the shared body is the
     * shape that most wants the defaults, and it is the one that breaks first.
     */
    @Test
    void tokensResolveThroughAConcreteSubclassOfAGenericBase() {
        StringOneShot tasklet = new StringOneShot();
        assertThat(tasklet.inputType()).isEqualTo(String.class);
        assertThat(tasklet.outputType()).isEqualTo(String.class);
        assertThat(tasklet.changeType()).isEqualTo(NoChange.class);
    }

    /**
     * fails when: an unresolvable argument is guessed instead of reported —
     * the wrong guess surfaces as a type mismatch at dispatch, far from the
     * declaration that caused it.
     */
    @Test
    void anUnresolvableTokenFailsNamingTheClassAndParameter() {
        assertThatThrownBy(() -> new StillGeneric<String>().inputType())
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining(StillGeneric.class.getName())
                .hasMessageContaining("Input")
                .hasMessageContaining("inputType");
    }

    /** fails when: a default starts winning over the explicit declaration it exists to spare. */
    @Test
    void anExplicitOverrideWinsOverTheDefault() {
        assertThat(new OverridingStillGeneric<String>().inputType()).isEqualTo(String.class);
        // The parameters it does NOT override still resolve.
        assertThat(new OverridingStillGeneric<String>().outputType()).isEqualTo(String.class);
    }

    /** fails when: the per-Cell codec stops being handed the two types it always needs. */
    @Test
    void kryoTypesDefaultToTheResolvedChangeAndState() {
        assertThat(new DeclaredTokens().kryoTypes()).containsExactly(SampleChange.class, Long.class);
    }

    /** A Tasklet declaring every type argument concretely and overriding no token method. */
    public static final class DeclaredTokens implements Tasklet<String, Long, Integer, SampleChange, WallClock, Void> {
        @Override
        public String name() {
            return "declared";
        }

        @Override
        public Long initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return 0L;
        }

        @Override
        public void run(String input, StateHandle<Long, SampleChange> state, TaskletCtx ctx) {}
    }

    /** A generic intermediate class: the shared body, one type argument still open. */
    public abstract static class OneShot<I> implements Tasklet<I, String, I, NoChange, WallClock, Void> {
        @Override
        public String initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return "";
        }

        @Override
        public void run(I input, StateHandle<String, NoChange> state, TaskletCtx ctx) {}
    }

    /** The concrete subclass that pins it. */
    public static final class StringOneShot extends OneShot<String> {
        @Override
        public String name() {
            return "string_one_shot";
        }
    }

    /** Concrete class, still-generic Input: nothing to resolve `Input` to. */
    public static class StillGeneric<I> implements Tasklet<I, String, String, NoChange, WallClock, Void> {
        @Override
        public String name() {
            return "still_generic";
        }

        @Override
        public String initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return "";
        }

        @Override
        public void run(I input, StateHandle<String, NoChange> state, TaskletCtx ctx) {}
    }

    /** The same, with the one unresolvable token declared explicitly. */
    public static final class OverridingStillGeneric<I> extends StillGeneric<I> {
        @Override
        @SuppressWarnings("unchecked")
        public Class<I> inputType() {
            return (Class<I>) String.class;
        }
    }

    public static final class StatelessTasklet implements Tasklet<String, String, String, NoChange, WallClock, Void> {
        @Override
        public String name() {
            return "stateless";
        }

        @Override
        public String initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return "init";
        }

        @Override
        public void run(String input, StateHandle<String, NoChange> state, TaskletCtx ctx) {}
    }

    /** Constructs once, then refuses once armed. */
    public static final class RefusingTasklet implements Tasklet<String, String, String, NoChange, WallClock, Void> {
        static final String REFUSAL = "the author's own refusal";
        static final AtomicBoolean REFUSE = new AtomicBoolean();

        public RefusingTasklet() {
            if (REFUSE.get()) {
                throw new IllegalStateException(REFUSAL);
            }
        }

        @Override
        public String name() {
            return "refusing";
        }

        @Override
        public String initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return "init";
        }

        @Override
        public void run(String input, StateHandle<String, NoChange> state, TaskletCtx ctx) {}
    }

    public record SampleChange(long delta) implements com.flezha.sdk.state.Change {}
}
