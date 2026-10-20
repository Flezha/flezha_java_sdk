package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.relay.TaskletModel.Hook;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What {@link TaskletModel} reads off a tasklet class: its hooks, types, name, settings and initial state. */
class TaskletModelTest {

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

    @Test
    @DisplayName("Java types resolved from the implements clause")
    void javaTypesResolvedFromTheImplementsClause() {
        TaskletModel model = TaskletModel.read(TrackPrices.class, new TrackPrices());
        assertThat(model.errors()).isEmpty();
        assertThat(model.inputType()).isEqualTo(Trade.class);
        assertThat(model.stateType()).isEqualTo(PriceWindow.class);
        assertThat(model.outputType()).isEqualTo(Move.class);
    }

    @Test
    @DisplayName("types resolve through a concrete subclass of a generic base")
    void typesResolveThroughAGenericBase() {
        TaskletModel model = TaskletModel.read(StringOneShot.class, new StringOneShot());
        assertThat(model.inputType()).isEqualTo(String.class);
        assertThat(model.outputType()).isEqualTo(String.class);
        assertThat(model.stateless()).isTrue();
    }

    @Test
    @DisplayName("Unresolvable type fails registration by name")
    void unresolvableTypeFailsRegistrationByName() {
        FlezhaException refused =
                TaskletModel.read(StillGeneric.class, null).errors().get(0);
        assertThat(refused.code()).isEqualTo("topology.unresolved_type_argument");
        assertThat(refused.details())
                .containsEntry("tasklet", StillGeneric.class.getName())
                .containsEntry("parameter", "Input");
    }

    @Test
    @DisplayName("Explicit override wins: a written kryoTypes() replaces discovery")
    void explicitOverrideWins() {
        TaskletModel listed = TaskletModel.read(ListedTypes.class, new ListedTypes());
        assertThat(listed.kryoTypesListed()).isTrue();
        assertThat(listed.kryoTypes()).containsExactly(PriceWindow.class);

        TaskletModel discovered = TaskletModel.read(TrackPrices.class, new TrackPrices());
        assertThat(discovered.kryoTypesListed()).isFalse();
        assertThat(discovered.kryoTypes()).contains(PriceWindow.class, TrackPrices.PriceSeen.class);
    }

    @Test
    @DisplayName("The annotation names the tasklet")
    void theAnnotationNamesTheTasklet() {
        assertThat(TaskletModel.read(QuietSymbol.class, null).name()).isEqualTo("quiet_symbol");
    }

    @Test
    @DisplayName("A renamed class keeps its state: the saved name is the annotation's, not the class's")
    void aRenamedClassKeepsItsState() {
        String before = TaskletModel.read(QuietSymbol.class, null).name();
        String after = TaskletModel.read(QuietPair.class, null).name();
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("Settings take effect from the annotation")
    void settingsTakeEffectFromTheAnnotation() {
        TaskletModel model = TaskletModel.read(QuietSymbol.class, null);
        assertThat(model.maxCells()).hasValue(1_000L);
        assertThat(model.cellIdleTtl()).contains(Duration.ofHours(1));
        assertThat(TaskletModel.read(QuietPair.class, null).maxCells())
                .as("the default is not stated")
                .isEmpty();
    }

    @Test
    @DisplayName("A tasklet without the annotation is refused with topology.tasklet_name_missing")
    void aTaskletWithoutTheAnnotationIsRefused() {
        FlezhaException refused =
                Refused.by("topology.tasklet_name_missing", () -> TaskletModel.read(NoSpec.class, null));
        assertThat(refused.details()).containsEntry("tasklet", NoSpec.class.getName());
    }

    @Test
    @DisplayName("A negative member is refused naming it and its value; an empty name too")
    void aNegativeMemberIsRefused() {
        FlezhaException negative =
                TaskletModel.read(NegativeTtl.class, null).errors().get(0);
        assertThat(negative.code()).isEqualTo("topology.negative_setting");
        assertThat(negative.details())
                .containsEntry("setting", "idleTtlSeconds")
                // Rendered by the core, the details are read back as JSON, where -1 is an int.
                .containsEntry("value", -1);
        assertThat(TaskletModel.read(EmptyName.class, null).errors())
                .singleElement()
                .extracting(FlezhaException::code)
                .isEqualTo("topology.tasklet_name_missing");
    }

    @Test
    @DisplayName("The constructor builds a new cell's state")
    void theConstructorBuildsANewCellsState() {
        TrackPrices tasklet = new TrackPrices();
        TaskletModel model = TaskletModel.read(TrackPrices.class, tasklet);
        Object first = model.initialState(tasklet, Optional.empty());
        assertThat(first).isInstanceOf(PriceWindow.class);
        assertThat(model.initialState(tasklet, Optional.empty())).isNotSameAs(first);
    }

    @Test
    @DisplayName("initState receives the cell key")
    void initStateReceivesTheCellKey() {
        KeyedWindow tasklet = new KeyedWindow();
        Object state = TaskletModel.read(KeyedWindow.class, tasklet).initialState(tasklet, Optional.of("1h"));
        assertThat(((Span) state).key()).isEqualTo("1h");
    }

    @Test
    @DisplayName("A state class without a constructor is refused naming the class")
    void aStateClassWithoutAConstructorIsRefused() {
        FlezhaException refused =
                TaskletModel.read(NoConstructor.class, null).errors().get(0);
        assertThat(refused.code()).isEqualTo("topology.state_not_constructible");
        assertThat(refused.details()).containsEntry("state", Span.class.getName());
    }

    @Tasklet.Spec(name = "bare")
    public static class Bare implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
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

    @Tasklet.Spec(name = "object_input")
    public static final class ObjectInput implements Tasklet<Object, Void, Object> {

        @Override
        public void run(Object input, Ctx<Void, Object> ctx) {}

        @Override
        public List<String> cellsFor(Object input) {
            return List.of();
        }

        @Override
        public void compensate(List<Object> completedOutput, Ctx<Void, Object> ctx) {}
    }

    public abstract static class Step extends Bare {
        @Override
        public void compensate(List<String> completedOutput, Ctx<Void, String> ctx) {}
    }

    public static final class Reserve extends Step {}

    public record Trade(String symbol, double price) {}

    public record Move(String symbol, double average) {}

    public static class PriceWindow {
        public double sum;
    }

    @Tasklet.Spec(name = "track_prices")
    public static final class TrackPrices implements Tasklet<Trade, PriceWindow, Move> {
        public record PriceSeen(double price) implements Change<PriceWindow> {
            @Override
            public void applyTo(PriceWindow window) {
                window.sum += price;
            }
        }

        @Override
        public void run(Trade trade, Ctx<PriceWindow, Move> ctx) {}
    }

    @Tasklet.Spec(name = "listed_types")
    public static final class ListedTypes implements Tasklet<Trade, PriceWindow, Move> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(PriceWindow.class);
        }

        @Override
        public void run(Trade trade, Ctx<PriceWindow, Move> ctx) {}
    }

    /** A generic intermediate class: the shared body, one type argument still open. */
    public abstract static class OneShot<I> implements Tasklet<I, Void, I> {
        @Override
        public void run(I input, Ctx<Void, I> ctx) {}
    }

    @Tasklet.Spec(name = "string_one_shot")
    public static final class StringOneShot extends OneShot<String> {}

    @Tasklet.Spec(name = "still_generic")
    public static class StillGeneric<I> implements Tasklet<I, Void, String> {
        @Override
        public void run(I input, Ctx<Void, String> ctx) {}
    }

    @Tasklet.Spec(name = "quiet_symbol", maxCells = 1_000, idleTtlSeconds = 3_600)
    public static final class QuietSymbol implements Tasklet<Trade, Void, Void> {
        @Override
        public void run(Trade trade, Ctx<Void, Void> ctx) {}
    }

    @Tasklet.Spec(name = "quiet_symbol")
    public static final class QuietPair implements Tasklet<Trade, Void, Void> {
        @Override
        public void run(Trade trade, Ctx<Void, Void> ctx) {}
    }

    public static final class NoSpec implements Tasklet<Trade, Void, Void> {
        @Override
        public void run(Trade trade, Ctx<Void, Void> ctx) {}
    }

    @Tasklet.Spec(name = "x", idleTtlSeconds = -1)
    public static final class NegativeTtl implements Tasklet<Trade, Void, Void> {
        @Override
        public void run(Trade trade, Ctx<Void, Void> ctx) {}
    }

    @Tasklet.Spec(name = "")
    public static final class EmptyName implements Tasklet<Trade, Void, Void> {
        @Override
        public void run(Trade trade, Ctx<Void, Void> ctx) {}
    }

    /** A state with no no-arg constructor. */
    public record Span(String key) {}

    @Tasklet.Spec(
            name = "keyed_window",
            cellKeys = {"1m", "1h"})
    public static final class KeyedWindow implements Tasklet<Trade, Span, Void> {
        @Override
        public Span initState(Optional<String> cellKey) {
            return new Span(cellKey.orElseThrow());
        }

        @Override
        public List<String> cellsFor(Trade trade) {
            return List.of("1m", "1h");
        }

        @Override
        public void run(Trade trade, Ctx<Span, Void> ctx) {}
    }

    @Tasklet.Spec(name = "no_constructor")
    public static final class NoConstructor implements Tasklet<Trade, Span, Void> {
        @Override
        public void run(Trade trade, Ctx<Span, Void> ctx) {}
    }
}
