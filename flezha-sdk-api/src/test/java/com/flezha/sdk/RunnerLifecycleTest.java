package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.UnboundedOperator;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runner lifecycle + JVM-wide singleton guard + on-demand define/remove
 * ({@code sdk-runner-entrypoint-redesign}). The runtime side is the test
 * double {@link com.flezha.sdk.runtime.FlezhaImpl} (implementing the
 * {@code RunnerHandle} SPI with fakeable live instances), so these tests
 * exercise the guard/lifecycle/declared-set logic without native transport;
 * the real runtime wiring is covered by the cross-language e2e suite.
 */
class RunnerLifecycleTest {

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    public static final class OperatorA implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "lifecycle_a";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class OperatorB implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "lifecycle_b";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class OperatorC implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "lifecycle_c";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class BoundedD implements BoundedOperator {
        @Override
        public String factoryId() {
            return "lifecycle_d";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    /**
     * A class of both kinds: its kind cannot be read, so it is refused at registration. The base
     * declares the one {@code channels} both kinds write, and both interfaces default
     * {@code persistenceEnabled()} and {@code sleepWhenIdle()}, so a class implementing both must
     * resolve the inherited defaults itself, which is what makes the shape writable at all.
     */
    public static final class BothKinds implements UnboundedOperator, BoundedOperator {
        @Override
        public String factoryId() {
            return "lifecycle_both";
        }

        @Override
        public void channels(ChannelBuilder c) {}

        @Override
        public boolean persistenceEnabled() {
            return true;
        }

        @Override
        public boolean sleepWhenIdle() {
            return false;
        }
    }

    /** A class of neither kind, reaching the runner through the shared base. */
    public static final class NeitherKind implements com.flezha.sdk.operator.Operator {
        @Override
        public String factoryId() {
            return "lifecycle_neither";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    /** A short form: the runner registers the operator generated from it, {@link ShortFormUnboundedOperator}. */
    @com.flezha.sdk.authoring.UnboundedOperator(id = "lifecycle_short", sink = "out")
    public static final class ShortForm {
        public String run(String input) {
            return input;
        }
    }

    /** A short form with no generated operator beside it, as when the processor never ran. */
    @com.flezha.sdk.authoring.BoundedOperator(id = "lifecycle_orphan")
    public static final class Orphan {
        public String run(String input) {
            return input;
        }
    }

    /** Neither an operator nor a short form. */
    public static final class NotAnOperator {}

    public static final class ThrowingConstructorOperator implements UnboundedOperator {
        public ThrowingConstructorOperator() {
            throw new IllegalStateException(AUTHORS_REFUSAL);
        }

        @Override
        public String factoryId() {
            return "lifecycle_throwing_constructor";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    @BeforeEach
    @AfterEach
    void releaseSingletonSlot() {
        Flezha.currentRunner().ifPresent(FlezhaRunner::close);
        com.flezha.sdk.runtime.FlezhaImpl.resetForTests();
        com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName(
            "second start() in the same JVM is rejected naming the running namespace, without reaching the runtime")
    void secondStartIsRejected() {
        FlezhaRunner first =
                Flezha.createRunner("ns1").registerFactory(OperatorA.class).start();
        int startsBefore = com.flezha.sdk.runtime.FlezhaImpl.STARTS.get();

        assertThatThrownBy(() -> Flezha.createRunner("ns2").start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ns1");

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.STARTS.get())
                .as("a rejected start() must not reach the runtime")
                .isEqualTo(startsBefore);
        assertThat(Flezha.currentRunner()).as("the active runner is untouched").containsSame(first);
        assertThat(first.getNamespace()).isEqualTo("ns1");
    }

    @Test
    @DisplayName("creating and mutating runners never claims the slot")
    void creatingRunnersNeverClaimsTheSlot() {
        FlezhaRunner active =
                Flezha.createRunner("ns1").registerFactory(OperatorA.class).start();

        assertThatCode(() -> Flezha.createRunner("ns2")
                        .registerFactory(OperatorB.class)
                        .unregisterFactory(OperatorB.class))
                .as("mutating an unstarted runner while another is active is legal")
                .doesNotThrowAnyException();

        assertThat(Flezha.currentRunner()).containsSame(active);
    }

    @Test
    @DisplayName("start() twice on the same runner is rejected")
    void doubleStartOnOneRunnerIsRejected() {
        FlezhaRunner runner = Flezha.createRunner("ns_double")
                .registerFactory(OperatorA.class)
                .start();
        assertThatThrownBy(runner::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    @DisplayName("zero-operator start is legal; the handshake supplier serves an empty set")
    void zeroOperatorStartIsLegal() {
        FlezhaRunner runner = Flezha.createRunner("ns_zero").start();
        assertThat(runner.getNamespace()).isEqualTo("ns_zero");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .as("the init handshake must carry an empty factory list")
                .isEmpty();
        // Types may be defined later on the live runner.
        runner.registerFactory(OperatorA.class);
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("a pre-start remove shapes the declared set the handshake reads")
    void preStartRemoveShapesTheHandshake() {
        FlezhaRunner runner = Flezha.createRunner("ns_shape")
                .registerFactory(OperatorA.class)
                .registerFactory(OperatorB.class)
                .unregisterFactory(OperatorB.class)
                .start();
        assertThat(runner.getNamespace()).isEqualTo("ns_shape");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .containsExactly("lifecycle_a");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .as("pre-start mutations ship nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a post-start define ships the full factory set")
    void postStartDefineShipsTheFullSet() {
        Flezha.createRunner("ns_def").registerFactory(OperatorA.class).start().registerFactory(OperatorC.class);

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .containsExactly(List.of("lifecycle_a", "lifecycle_c"));
    }

    @Test
    @DisplayName("a post-start remove (no live instances) ships the remaining full set")
    void postStartRemoveShipsTheRemainingSet() {
        Flezha.createRunner("ns_rem")
                .registerFactory(OperatorA.class)
                .registerFactory(OperatorC.class)
                .start()
                .unregisterFactory(OperatorC.class);

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).containsExactly(List.of("lifecycle_a"));
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .as("the runtime-side class must be unregistered")
                .doesNotContain("lifecycle_c");
    }

    @Test
    @DisplayName("a bounded class is defined and removed through the bounded registry and declared beside the streams")
    void boundedDefineAndRemoveReachTheBoundedRegistry() {
        FlezhaRunner runner = Flezha.createRunner("ns_bounded").registerFactory(BoundedD.class);
        assertThat(com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.registeredIds())
                .containsExactly("lifecycle_d");

        runner.unregisterFactory(BoundedD.class);
        assertThat(com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.registeredIds())
                .as("a pre-start remove unregisters the runtime-side class")
                .isEmpty();

        runner.registerFactory(OperatorA.class).registerFactory(BoundedD.class).start();
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .containsExactly("lifecycle_a", "lifecycle_d");
    }

    @Test
    @DisplayName("removing a type with live instances throws OperatorTypeInUseException and keeps it defined")
    void removeWithLiveInstancesRefuses() {
        FlezhaRunner runner =
                Flezha.createRunner("ns_live").registerFactory(OperatorA.class).start();
        com.flezha.sdk.runtime.FlezhaImpl.LIVE_INSTANCES.put("lifecycle_a", 1);

        assertThatThrownBy(() -> runner.unregisterFactory(OperatorA.class))
                .isInstanceOf(OperatorTypeInUseException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lifecycle_a");

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .as("the type stays defined after a refused removal")
                .containsExactly("lifecycle_a");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .as("a refused removal ships nothing")
                .isEmpty();

        // Once the instances stop, the removal succeeds.
        com.flezha.sdk.runtime.FlezhaImpl.LIVE_INSTANCES.remove("lifecycle_a");
        runner.unregisterFactory(OperatorA.class);
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).containsExactly(List.of());
    }

    @Test
    @DisplayName("removing an undefined type throws IllegalArgumentException naming the factory id")
    void removeUndefinedTypeIsRejected() {
        FlezhaRunner runner = Flezha.createRunner("ns_undef").registerFactory(OperatorA.class);
        assertThatThrownBy(() -> runner.unregisterFactory(OperatorB.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle_b");
        // Already-removed counts as undefined too.
        runner.unregisterFactory(OperatorA.class);
        assertThatThrownBy(() -> runner.unregisterFactory(OperatorA.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle_a");
    }

    @Test
    @DisplayName("defining a duplicate factory id on one runner is rejected")
    void duplicateFactoryIdIsRejected() {
        FlezhaRunner runner = Flezha.createRunner("ns_dup").registerFactory(OperatorA.class);
        assertThatThrownBy(() -> runner.registerFactory(OperatorA.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("DuplicateFactoryId")
                .hasMessageContaining("lifecycle_a");
    }

    @Test
    @DisplayName("one registerFactory takes either kind")
    void oneMethodRegistersEitherKind() {
        FlezhaRunner runner = Flezha.createRunner("ns_both_kinds")
                .registerFactory(OperatorA.class)
                .registerFactory(BoundedD.class)
                .start();

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).isEmpty();
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .containsExactlyInAnyOrder("lifecycle_a", "lifecycle_d");

        runner.unregisterFactory(BoundedD.class);
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("A class of both kinds is refused at registration, and so is one of neither")
    void anUnreadableKindIsRefused() {
        FlezhaRunner runner = Flezha.createRunner("ns_kind_refusals");

        assertThatThrownBy(() -> runner.registerFactory(BothKinds.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("AmbiguousOperatorKind")
                .hasMessageContaining("BothKinds");

        assertThatThrownBy(() -> runner.registerFactory(NeitherKind.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("UnknownOperatorKind")
                .hasMessageContaining("NeitherKind");

        assertThatThrownBy(() -> runner.registerFactory(NotAnOperator.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("UnknownOperatorKind")
                .hasMessageContaining("NotAnOperator")
                .hasMessageContaining("@UnboundedOperator");

        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .isEmpty();
    }

    @Test
    @DisplayName("the author registers the class they wrote, and a hand-written operator beside it")
    void theAuthorRegistersTheClassTheyWrote() {
        FlezhaRunner runner = Flezha.createRunner("ns_short_form")
                .registerFactory(ShortForm.class)
                .registerFactory(OperatorA.class);
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .containsExactly("lifecycle_short", "lifecycle_a");

        runner.unregisterFactory(ShortForm.class);
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .as("unregister takes what register took")
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("a short form whose generated operator is missing is refused naming the processor")
    void aShortFormWithoutItsGeneratedOperatorIsRefused() {
        FlezhaRunner runner = Flezha.createRunner("ns_orphan");

        assertThatThrownBy(() -> runner.registerFactory(Orphan.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OrphanBoundedOperator")
                .hasMessageContaining("flezha-sdk-processor");
    }

    @Test
    @DisplayName("pre-start awaitTermination()/getOperators() throw ISE; close() is a no-op")
    void preStartLifecycleCalls() {
        FlezhaRunner runner = Flezha.createRunner("ns_cold");
        assertThatThrownBy(runner::awaitTermination)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
        assertThatThrownBy(runner::getOperators)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
        assertThatCode(runner::close).doesNotThrowAnyException();
        assertThat(Flezha.currentRunner())
                .as("a never-started runner never appears")
                .isEmpty();
    }

    @Test
    @DisplayName("define/remove on a closed runner are refused and never reach the runtime")
    void mutatingAClosedRunnerIsRefused() {
        FlezhaRunner runner = Flezha.createRunner("ns_closed")
                .registerFactory(OperatorA.class)
                .start();
        runner.close();

        // close() destroys the core context; a mutation that got through would
        // carry the freed handle back across the JNI boundary.
        assertThatThrownBy(() -> runner.registerFactory(OperatorB.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ns_closed");
        assertThatThrownBy(() -> runner.registerFactory(BoundedD.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ns_closed");
        assertThatThrownBy(() -> runner.unregisterFactory(OperatorA.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ns_closed");
        assertThatThrownBy(() -> runner.unregisterFactory(BoundedD.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ns_closed");

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .as("a refused mutation must not reach the runtime")
                .isEmpty();
        assertThat(com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry.registeredIds())
                .as("a refused define must not register the class")
                .doesNotContain("lifecycle_b");
    }

    @Test
    @DisplayName("a runner may be started again after the first one closes")
    void restartAfterCloseSucceeds() {
        Flezha.createRunner("ns1").registerFactory(OperatorA.class).start().close();
        assertThat(Flezha.currentRunner()).isEmpty();

        FlezhaRunner second =
                Flezha.createRunner("ns2").registerFactory(OperatorB.class).start();
        assertThat(second.getNamespace()).isEqualTo("ns2");
        assertThat(Flezha.currentRunner()).containsSame(second);
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .as("generation 2's declared set starts empty and re-defines its own types")
                .containsExactly("lifecycle_b");
    }

    @Test
    @DisplayName("try-with-resources starts the runner for the block and closes it at the end")
    void tryWithResourcesWorks() {
        try (FlezhaRunner r =
                Flezha.createRunner("ns_try").registerFactory(OperatorA.class).start()) {
            assertThat(r.getNamespace()).isEqualTo("ns_try");
            assertThat(Flezha.currentRunner()).containsSame(r);
        }
        assertThat(Flezha.currentRunner()).isEmpty();
    }

    @Test
    @DisplayName("close() from another thread wakes a thread blocked in awaitTermination()")
    void closeWakesAwaitTermination() throws Exception {
        FlezhaRunner runner =
                Flezha.createRunner("ns_await").registerFactory(OperatorA.class).start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = pool.submit(() -> {
                runner.awaitTermination();
                return null;
            });
            assertThatThrownBy(() -> blocked.get(150, TimeUnit.MILLISECONDS))
                    .as("awaitTermination must block while the runner is live")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            runner.close();
            blocked.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("currentRunner() reflects empty -> active -> empty across the lifecycle")
    void currentRunnerReflectsLifecycle() {
        assertThat(Flezha.currentRunner()).isEmpty();
        FlezhaRunner runner =
                Flezha.createRunner("ns_cur").registerFactory(OperatorA.class).start();
        assertThat(Flezha.currentRunner()).containsSame(runner);
        runner.close();
        assertThat(Flezha.currentRunner()).isEmpty();
    }

    @Test
    @DisplayName("two concurrent start() calls — exactly one wins, the loser is told the winner's namespace")
    void concurrentStartsExactlyOneWins() throws Exception {
        FlezhaRunner r1 = Flezha.createRunner("ns_one").registerFactory(OperatorA.class);
        FlezhaRunner r2 = Flezha.createRunner("ns_two").registerFactory(OperatorB.class);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> f1 = pool.submit(() -> race(barrier, r1));
            Future<Object> f2 = pool.submit(() -> race(barrier, r2));
            Object o1 = f1.get(10, TimeUnit.SECONDS);
            Object o2 = f2.get(10, TimeUnit.SECONDS);

            FlezhaRunner winner;
            IllegalStateException loser;
            if (o1 instanceof FlezhaRunner w) {
                winner = w;
                loser = (IllegalStateException) o2;
            } else {
                winner = (FlezhaRunner) o2;
                loser = (IllegalStateException) o1;
            }
            assertThat(loser)
                    .as("the loser must be told the winner's namespace")
                    .hasMessageContaining(winner.getNamespace());
            assertThat(Flezha.currentRunner()).containsSame(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("removing an undefined type whose constructor throws carries the author's error")
    void removeUndefinedTypeWithThrowingConstructorCarriesTheAuthorsError() {
        FlezhaRunner runner = Flezha.createRunner("ns_throwing_ctor");

        Throwable thrown = catchThrowable(() -> runner.unregisterFactory(ThrowingConstructorOperator.class));

        assertThat(thrown).isNotNull();
        assertThat(Arrays.asList(thrown, thrown.getCause()))
                .as("the author's exception is the one thrown or its direct cause")
                .anySatisfy(t ->
                        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage(AUTHORS_REFUSAL));
    }

    private static Object race(CyclicBarrier barrier, FlezhaRunner runner) throws Exception {
        barrier.await();
        try {
            return runner.start();
        } catch (IllegalStateException e) {
            return e;
        }
    }

    @Test
    @DisplayName("createRunner rejects a null or empty namespace")
    void createRunnerValidatesNamespace() {
        assertThatThrownBy(() -> Flezha.createRunner("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Flezha.createRunner(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
