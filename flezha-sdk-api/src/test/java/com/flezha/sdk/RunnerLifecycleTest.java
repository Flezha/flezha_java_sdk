package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
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

    public static final class RelayA implements Relay {
        @Override
        public String factoryId() {
            return "lifecycle_a";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class RelayB implements Relay {
        @Override
        public String factoryId() {
            return "lifecycle_b";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class RelayC implements Relay {
        @Override
        public String factoryId() {
            return "lifecycle_c";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public static final class StepsD implements Relay {
        @Override
        public String factoryId() {
            return "lifecycle_d";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    /** A short form: the tasklet class itself declares the relay. */
    @com.flezha.sdk.authoring.Relay(id = "lifecycle_short", output = "out")
    public static final class ShortForm implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    /** Neither a relay nor a short form. */
    public static final class NotARelay {}

    public static final class ThrowingConstructorRelay implements Relay {
        public ThrowingConstructorRelay() {
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
        com.flezha.sdk.runtime.relay.RelayClassRegistry.reset();
    }

    @Test
    @DisplayName(
            "second start() in the same JVM is rejected naming the running namespace, without reaching the runtime")
    void secondStartIsRejected() {
        FlezhaRunner first =
                Flezha.createRunner("ns1").registerFactory(RelayA.class).start();
        int startsBefore = com.flezha.sdk.runtime.FlezhaImpl.STARTS.get();

        assertThatThrownBy(() -> Flezha.createRunner("ns2").start())
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.runner_already_running");
                    assertThat(e.details()).containsEntry("namespace", "ns1");
                });

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
                Flezha.createRunner("ns1").registerFactory(RelayA.class).start();

        assertThatCode(() ->
                        Flezha.createRunner("ns2").registerFactory(RelayB.class).unregisterFactory(RelayB.class))
                .as("mutating an unstarted runner while another is active is legal")
                .doesNotThrowAnyException();

        assertThat(Flezha.currentRunner()).containsSame(active);
    }

    @Test
    @DisplayName("start() twice on the same runner is rejected")
    void doubleStartOnOneRunnerIsRejected() {
        FlezhaRunner runner =
                Flezha.createRunner("ns_double").registerFactory(RelayA.class).start();
        assertThatThrownBy(runner::start).isInstanceOfSatisfying(FlezhaException.class, e -> {
            assertThat(e.code()).isEqualTo("runtime.runner_already_running");
        });
    }

    @Test
    @DisplayName("zero-relay start is legal; the handshake supplier serves an empty set")
    void zeroRelayStartIsLegal() {
        FlezhaRunner runner = Flezha.createRunner("ns_zero").start();
        assertThat(runner.getNamespace()).isEqualTo("ns_zero");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .as("the init handshake must carry an empty factory list")
                .isEmpty();
        // Types may be defined later on the live runner.
        runner.registerFactory(RelayA.class);
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("a pre-start remove shapes the declared set the handshake reads")
    void preStartRemoveShapesTheHandshake() {
        FlezhaRunner runner = Flezha.createRunner("ns_shape")
                .registerFactory(RelayA.class)
                .registerFactory(RelayB.class)
                .unregisterFactory(RelayB.class)
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
        Flezha.createRunner("ns_def").registerFactory(RelayA.class).start().registerFactory(RelayC.class);

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .containsExactly(List.of("lifecycle_a", "lifecycle_c"));
    }

    @Test
    @DisplayName("a post-start remove (no live instances) ships the remaining full set")
    void postStartRemoveShipsTheRemainingSet() {
        Flezha.createRunner("ns_rem")
                .registerFactory(RelayA.class)
                .registerFactory(RelayC.class)
                .start()
                .unregisterFactory(RelayC.class);

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).containsExactly(List.of("lifecycle_a"));
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .as("the runtime-side class must be unregistered")
                .doesNotContain("lifecycle_c");
    }

    @Test
    @DisplayName("a steps class is defined and removed through the one registry and declared beside the flows")
    void stepsDefineAndRemoveReachTheOneRegistry() {
        FlezhaRunner runner = Flezha.createRunner("ns_steps").registerFactory(StepsD.class);
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .containsExactly("lifecycle_d");

        runner.unregisterFactory(StepsD.class);
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .as("a pre-start remove unregisters the runtime-side class")
                .isEmpty();

        runner.registerFactory(RelayA.class).registerFactory(StepsD.class).start();
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .containsExactly("lifecycle_a", "lifecycle_d");
    }

    @Test
    @DisplayName("removing a type with live instances throws runtime.relay_type_in_use and keeps it defined")
    void removeWithLiveInstancesRefuses() {
        FlezhaRunner runner =
                Flezha.createRunner("ns_live").registerFactory(RelayA.class).start();
        com.flezha.sdk.runtime.FlezhaImpl.LIVE_INSTANCES.put("lifecycle_a", 1);

        assertThatThrownBy(() -> runner.unregisterFactory(RelayA.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.relay_type_in_use");
                    assertThat(e.details())
                            .containsEntry("factory_id", "lifecycle_a")
                            .containsEntry("live_instances", 1);
                });

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.LAST_DECLARED_SUPPLIER.get())
                .as("the type stays defined after a refused removal")
                .containsExactly("lifecycle_a");
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .as("a refused removal ships nothing")
                .isEmpty();

        // Once the instances stop, the removal succeeds.
        com.flezha.sdk.runtime.FlezhaImpl.LIVE_INSTANCES.remove("lifecycle_a");
        runner.unregisterFactory(RelayA.class);
        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).containsExactly(List.of());
    }

    @Test
    @DisplayName("removing an undefined type throws runtime.class_not_registered naming the factory id")
    void removeUndefinedTypeIsRejected() {
        FlezhaRunner runner = Flezha.createRunner("ns_undef").registerFactory(RelayA.class);
        assertThatThrownBy(() -> runner.unregisterFactory(RelayB.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.class_not_registered");
                    assertThat(e.details()).containsEntry("factory", "lifecycle_b");
                });
        // Already-removed counts as undefined too.
        runner.unregisterFactory(RelayA.class);
        assertThatThrownBy(() -> runner.unregisterFactory(RelayA.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.class_not_registered");
                    assertThat(e.details()).containsEntry("factory", "lifecycle_a");
                });
    }

    @Test
    @DisplayName("defining a duplicate factory id on one runner is rejected")
    void duplicateFactoryIdIsRejected() {
        FlezhaRunner runner = Flezha.createRunner("ns_dup").registerFactory(RelayA.class);
        assertThatThrownBy(() -> runner.registerFactory(RelayA.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("topology.duplicate_factory_id");
                    assertThat(e.details()).containsEntry("factory_id", "lifecycle_a");
                });
    }

    @Test
    @DisplayName("one registerFactory takes either run")
    void oneMethodRegistersEitherRun() {
        FlezhaRunner runner = Flezha.createRunner("ns_both_runs")
                .registerFactory(RelayA.class)
                .registerFactory(StepsD.class)
                .start();

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS).isEmpty();
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .containsExactlyInAnyOrder("lifecycle_a", "lifecycle_d");

        runner.unregisterFactory(StepsD.class);
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("A class that is neither a relay nor a short form is refused at registration")
    void aClassThatIsNoRelayIsRefused() {
        FlezhaRunner runner = Flezha.createRunner("ns_no_relay");

        assertThatThrownBy(() -> runner.registerFactory(NotARelay.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("topology.no_relay_exported");
                    assertThat(e.details()).containsEntry("module", NotARelay.class.getName());
                });

        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .isEmpty();
    }

    @Test
    @DisplayName("the author registers the class they wrote, and a hand-written relay beside it")
    void theAuthorRegistersTheClassTheyWrote() {
        FlezhaRunner runner = Flezha.createRunner("ns_short_form")
                .registerFactory(ShortForm.class)
                .registerFactory(RelayA.class);
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .containsExactly("lifecycle_short", "lifecycle_a");

        runner.unregisterFactory(ShortForm.class);
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .as("unregister takes what register took")
                .containsExactly("lifecycle_a");
    }

    @Test
    @DisplayName("pre-start awaitTermination()/getRelays() throw runtime.runner_not_started; close() is a no-op")
    void preStartLifecycleCalls() {
        FlezhaRunner runner = Flezha.createRunner("ns_cold");
        assertThatThrownBy(runner::awaitTermination).isInstanceOfSatisfying(FlezhaException.class, e -> {
            assertThat(e.code()).isEqualTo("runtime.runner_not_started");
        });
        assertThatThrownBy(runner::getRelays).isInstanceOfSatisfying(FlezhaException.class, e -> {
            assertThat(e.code()).isEqualTo("runtime.runner_not_started");
        });
        assertThatCode(runner::close).doesNotThrowAnyException();
        assertThat(Flezha.currentRunner())
                .as("a never-started runner never appears")
                .isEmpty();
    }

    @Test
    @DisplayName("define/remove on a closed runner are refused and never reach the runtime")
    void mutatingAClosedRunnerIsRefused() {
        FlezhaRunner runner =
                Flezha.createRunner("ns_closed").registerFactory(RelayA.class).start();
        runner.close();

        // close() destroys the core context; a mutation that got through would
        // carry the freed handle back across the JNI boundary.
        assertThatThrownBy(() -> runner.registerFactory(RelayB.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.runner_closed");
                    assertThat(e.details()).containsEntry("namespace", "ns_closed");
                });
        assertThatThrownBy(() -> runner.registerFactory(StepsD.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.runner_closed");
                    assertThat(e.details()).containsEntry("namespace", "ns_closed");
                });
        assertThatThrownBy(() -> runner.unregisterFactory(RelayA.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.runner_closed");
                    assertThat(e.details()).containsEntry("namespace", "ns_closed");
                });
        assertThatThrownBy(() -> runner.unregisterFactory(StepsD.class))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("runtime.runner_closed");
                    assertThat(e.details()).containsEntry("namespace", "ns_closed");
                });

        assertThat(com.flezha.sdk.runtime.FlezhaImpl.SYNCED_SETS)
                .as("a refused mutation must not reach the runtime")
                .isEmpty();
        assertThat(com.flezha.sdk.runtime.relay.RelayClassRegistry.registeredIds())
                .as("a refused define must not register the class")
                .doesNotContain("lifecycle_b");
    }

    @Test
    @DisplayName("a runner may be started again after the first one closes")
    void restartAfterCloseSucceeds() {
        Flezha.createRunner("ns1").registerFactory(RelayA.class).start().close();
        assertThat(Flezha.currentRunner()).isEmpty();

        FlezhaRunner second =
                Flezha.createRunner("ns2").registerFactory(RelayB.class).start();
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
                Flezha.createRunner("ns_try").registerFactory(RelayA.class).start()) {
            assertThat(r.getNamespace()).isEqualTo("ns_try");
            assertThat(Flezha.currentRunner()).containsSame(r);
        }
        assertThat(Flezha.currentRunner()).isEmpty();
    }

    @Test
    @DisplayName("close() from another thread wakes a thread blocked in awaitTermination()")
    void closeWakesAwaitTermination() throws Exception {
        FlezhaRunner runner =
                Flezha.createRunner("ns_await").registerFactory(RelayA.class).start();
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
                Flezha.createRunner("ns_cur").registerFactory(RelayA.class).start();
        assertThat(Flezha.currentRunner()).containsSame(runner);
        runner.close();
        assertThat(Flezha.currentRunner()).isEmpty();
    }

    @Test
    @DisplayName("two concurrent start() calls — exactly one wins, the loser is told the winner's namespace")
    void concurrentStartsExactlyOneWins() throws Exception {
        FlezhaRunner r1 = Flezha.createRunner("ns_one").registerFactory(RelayA.class);
        FlezhaRunner r2 = Flezha.createRunner("ns_two").registerFactory(RelayB.class);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> f1 = pool.submit(() -> race(barrier, r1));
            Future<Object> f2 = pool.submit(() -> race(barrier, r2));
            Object o1 = f1.get(10, TimeUnit.SECONDS);
            Object o2 = f2.get(10, TimeUnit.SECONDS);

            FlezhaRunner winner;
            FlezhaException loser;
            if (o1 instanceof FlezhaRunner w) {
                winner = w;
                loser = (FlezhaException) o2;
            } else {
                winner = (FlezhaRunner) o2;
                loser = (FlezhaException) o1;
            }
            assertThat(loser.details())
                    .as("the loser must be told the winner's namespace")
                    .containsEntry("namespace", winner.getNamespace());
            assertThat(Flezha.currentRunner()).containsSame(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("removing an undefined type whose constructor throws carries the author's error")
    void removeUndefinedTypeWithThrowingConstructorCarriesTheAuthorsError() {
        FlezhaRunner runner = Flezha.createRunner("ns_throwing_ctor");

        Throwable thrown = catchThrowable(() -> runner.unregisterFactory(ThrowingConstructorRelay.class));

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
        } catch (FlezhaException e) {
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
