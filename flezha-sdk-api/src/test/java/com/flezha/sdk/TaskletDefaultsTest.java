package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The defaults a tasklet gets for what it does not write: the {@code @Tasklet.Spec} members and the hooks. */
class TaskletDefaultsTest {

    private static final Tasklet.Spec SPEC = Minimal.class.getAnnotation(Tasklet.Spec.class);

    @Test
    void theSpecIsReadAtRuntime() {
        assertThat(SPEC).isNotNull();
        assertThat(SPEC.name()).isEqualTo("minimal");
    }

    @Test
    void compactAfterBytesDefaultsTo8Mib() {
        // 8 MiB, in lockstep with Rust and the controller's default.
        assertThat(SPEC.compactAfterBytes()).isEqualTo(8388608L);
    }

    @Test
    void schemaVersionDefaultsToOneAndReadsOnlyItself() {
        assertThat(SPEC.schemaVersion()).isEqualTo(1);
        assertThat(SPEC.minReadableSchemaVersion()).isZero();
    }

    @Test
    void theCellSettingsDefaultToTheRuntimesDefaults() {
        assertThat(SPEC.cellKeys()).isEmpty();
        assertThat(SPEC.mailboxCapacity()).as("unset").isEqualTo(-1);
        assertThat(SPEC.maxCells()).isEqualTo(100_000L);
        assertThat(SPEC.idleTtlSeconds()).isZero();
        assertThat(SPEC.maxTimersPerCell()).isEqualTo(10_000);
        assertThat(SPEC.logicalClock()).isFalse();
    }

    @Test
    void theOptionalHooksDefaultToNothing() throws Exception {
        Minimal tasklet = new Minimal();
        assertThat(tasklet.kryoTypes()).isEmpty();
        assertThat(tasklet.cellsFor("x")).isEmpty();
        tasklet.compensate(List.of(), null);
    }

    @Test
    void anUnwrittenHookTheRuntimeWouldCallFailsNamingTheTasklet() {
        Minimal tasklet = new Minimal();
        String name = "tasklet `minimal`";
        assertThatThrownBy(() -> tasklet.onTimer(new TimerFiring(TimerKind.PROCESSING_TIME, 1L), null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(name)
                .hasMessageContaining("onTimer");
        assertThatThrownBy(() -> tasklet.eventTime("x"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(name);
        assertThatThrownBy(() -> tasklet.initState(Optional.empty()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(name);
        assertThatThrownBy(() -> tasklet.migrateState(1, new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version 1");
        assertThatThrownBy(() -> tasklet.migrateChange(1, new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema_version 1");
    }

    @Tasklet.Spec(name = "minimal")
    public static final class Minimal implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }
}
