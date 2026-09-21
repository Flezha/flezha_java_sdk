package com.flezha.sdk.runtime.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.LoopMode;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.operative.WorkflowOperativeConfigurationException;
import com.flezha.sdk.runtime.operative.StatelessTestTasklet;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Registration-time refusals of {@link WorkflowSpec#build} — the authoring
 * validation half of the retired driver suite. Execution semantics (barriers,
 * loops, retries, sagas) are core-owned now; what stays host-side is the
 * refusal of a shape that could not run.
 */
class WorkflowSpecValidationTest {

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    @Test
    @DisplayName("a structured chain resolves its topological order")
    void structuredChainResolves() {
        WorkflowSpec spec = WorkflowSpec.build(LinearWorkflow.class);
        assertThat(spec.chain()).containsExactly(0, 1);
        assertThat(spec.loopMode()).isEqualTo(LoopMode.ONCE);
    }

    @Test
    @DisplayName("a workflow that declares no tasklet is refused")
    void noTaskletsIsRefused() {
        assertThatThrownBy(() -> WorkflowSpec.build(NoTaskletsWorkflow.class))
                .isInstanceOf(WorkflowOperativeConfigurationException.class)
                .hasMessageContaining("vb_no_tasklets")
                .hasMessageContaining("at least one tasklet");
    }

    @Test
    @DisplayName("more than one attempt with a zero backoff would hot-spin and is refused")
    void zeroRetryBackoffIsRefused() {
        assertThatThrownBy(() -> WorkflowSpec.build(ZeroBackoffWorkflow.class))
                .isInstanceOf(WorkflowOperativeConfigurationException.class)
                .hasMessageContaining("backoff");
    }

    @Test
    @DisplayName("an entry-channel edge closure is refused — the workflow seed names no channel")
    void entryEdgeClosureIsRefused() {
        assertThatThrownBy(() -> WorkflowSpec.build(EntryClosureWorkflow.class))
                .isInstanceOf(WorkflowOperativeConfigurationException.class)
                .hasMessageContaining("MergeFromEntryChannel");
    }

    @Test
    @DisplayName("a cycle is refused — a bounded topology is a DAG")
    void cycleIsRefused() {
        assertThatThrownBy(() -> WorkflowSpec.build(CyclicWorkflow.class))
                .isInstanceOf(WorkflowOperativeConfigurationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("an operative whose only exit is an external stop is refused")
    void externalStopOnlyIsRefused() {
        assertThatThrownBy(() -> WorkflowSpec.build(NeverEndingWorkflow.class))
                .isInstanceOf(WorkflowOperativeConfigurationException.class)
                .hasMessageContaining("terminable");
    }

    @Test
    @DisplayName("a constructor that throws is reported as the author's error, not as a missing constructor")
    void throwingConstructorIsReportedAsTheAuthorsError() {
        Throwable thrown = catchThrowable(() -> WorkflowSpec.build(ThrowingConstructorWorkflow.class));

        assertThat(thrown).hasMessageNotContaining("no-arg constructor");
        assertThat(Arrays.asList(thrown, thrown.getCause()))
                .as("the author's exception is the one thrown or its direct cause")
                .anySatisfy(t ->
                        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage(AUTHORS_REFUSAL));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    public static final class EchoTask extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx)
                throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    public static final class SinkTask extends StatelessTestTasklet<String, Object, Void> {
        @Override
        public String name() {
            return "sink";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class LinearWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_linear";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void tasklets(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class NoTaskletsWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_no_tasklets";
        }
    }

    public static final class ZeroBackoffWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_zero_backoff";
        }

        @Override
        public Duration retryBackoff() {
            return Duration.ZERO;
        }

        @Override
        public void tasklets(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class EntryClosureWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_entry_closure";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void tasklets(ChannelBuilder c) {
            c.input(String.class, "seed").map(String.class, String::toUpperCase).into(SinkTask.class);
        }
    }

    public static final class CyclicWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_cycle";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void tasklets(ChannelBuilder c) {
            var a = c.input(String.class, "seed").into(EchoTask.class);
            var b = a.into(EchoTask.class, "echo_b");
            b.into(EchoTask.class);
        }
    }

    public static final class NeverEndingWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "vb_never_ending";
        }

        @Override
        public boolean selfTerminating() {
            return false;
        }

        @Override
        public void tasklets(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class ThrowingConstructorWorkflow implements WorkflowOperative {
        public ThrowingConstructorWorkflow() {
            throw new IllegalStateException(AUTHORS_REFUSAL);
        }

        @Override
        public String factoryId() {
            return "vb_throwing_constructor";
        }
    }
}
