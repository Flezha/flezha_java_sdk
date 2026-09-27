package com.flezha.sdk.runtime.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.operator.TaskletModel;
import com.flezha.sdk.runtime.state.moved.MovedDesk;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.StateSerializeException;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which types a tasklet's codec registers, and the ids they take. */
class KryoTypesTest {

    @Test
    @DisplayName("discovery finds the state, its nested change records, and the user types their fields reach")
    void discoveryFindsStateChangesAndReachedTypes() {
        assertThat(KryoTypes.discover(Window.class, Window.State.class))
                .containsExactlyInAnyOrder(
                        Window.State.class,
                        Window.Level.class,
                        Window.Seen.class,
                        Window.Reset.class,
                        Window.Shape.Up.class,
                        Window.Shape.Down.class);
    }

    @Test
    @DisplayName("a Void state with no change records registers nothing")
    void aVoidStateRegistersNothing() {
        assertThat(KryoTypes.discover(NoState.class, Void.class)).isEmpty();
    }

    @Test
    @DisplayName("Adding a change record moves no other id: a saved record still decodes as its class")
    void addingAChangeRecordMovesNoOtherId() {
        KryoCodec before = windowCodec(Window.State.class, Window.Level.class, Window.Seen.class);
        byte[] saved = before.serialize(new Window.Seen(1.0853));

        // Reset sorts before Seen: an id from list or name order would move Seen's.
        KryoCodec after = windowCodec(Window.Reset.class, Window.State.class, Window.Level.class, Window.Seen.class);

        assertThat(after.deserialize(saved, Object.class)).isEqualTo(new Window.Seen(1.0853));
    }

    @Test
    @DisplayName("An explicit list owns the ids, in list order after the built-ins")
    void anExplicitListOwnsTheIds() {
        int first = classIdOf(new KryoCodec(List.of(Window.Level.class)), new Window.Level(1));
        KryoCodec listed = new KryoCodec(List.of(Window.Level.class, Window.Reset.class));

        assertThat(classIdOf(listed, new Window.Level(1))).as("N+1").isEqualTo(first);
        assertThat(classIdOf(listed, new Window.Reset())).as("N+2").isEqualTo(first + 1);
        assertThat(first)
                .as("listed ids sit below the derived band")
                .isLessThan(windowIds(Window.Level.class).get(Window.Level.class));
    }

    @Test
    @DisplayName("Colliding derived ids fail registration naming both classes")
    void collidingDerivedIdsFailRegistration() {
        Map<Class<?>, Integer> ids =
                KryoTypes.ids(List.of(Colliding.C150.class, Colliding.C917.class), Colliding.class, "colliding");
        assertThat(ids.get(Colliding.C150.class)).isEqualTo(ids.get(Colliding.C917.class));

        List<String> errors =
                TaskletModel.read(Colliding.class, new Colliding()).errors();

        assertThat(errors)
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(Colliding.C150.class.getName())
                .contains(Colliding.C917.class.getName())
                .contains("kryoTypes()");
    }

    @Test
    @DisplayName("Unregistered class fails loud at the first flush, naming the class and both fixes")
    void unregisteredClassFailsLoud() {
        KryoCodec codec = windowCodec(Window.State.class);

        assertThatThrownBy(() -> codec.serialize(new Outside("x")))
                .isInstanceOf(StateSerializeException.UnregisteredClass.class)
                .hasMessageStartingWith("UnregisteredClass")
                .hasMessageContaining(Outside.class.getName())
                .hasMessageContaining("declare it as a member class of the tasklet or state class")
                .hasMessageContaining("kryoTypes()");
    }

    @Test
    @DisplayName("an unregistered class reached through a field is named too")
    void anUnregisteredFieldClassIsNamed() {
        KryoCodec codec = windowCodec(Holder.class);

        assertThatThrownBy(() -> codec.serialize(new Holder(new Outside("x"))))
                .isInstanceOf(StateSerializeException.UnregisteredClass.class)
                .hasMessageContaining(Outside.class.getName());
    }

    @Test
    @DisplayName("A renamed class keeps its state: a tasklet renamed and moved decodes what the old class saved")
    void aRenamedTaskletClassDecodesWhatTheOldClassSaved() {
        TaskletModel before = TaskletModel.read(Desk.class, new Desk());
        Desk.Window window = new Desk.Window();
        window.count = 7;
        byte[] savedState = before.codec(before.kryoTypes()).serialize(window);
        byte[] savedChange = before.codec(before.kryoTypes()).serialize(new Desk.Seen(3));

        MovedDesk.RenamedDesk renamed = new MovedDesk.RenamedDesk();
        TaskletModel after = TaskletModel.read(MovedDesk.RenamedDesk.class, renamed);
        KryoCodec codec = after.codec(after.kryoTypes());

        assertThat(codec.deserialize(savedState, Object.class))
                .isInstanceOfSatisfying(MovedDesk.RenamedDesk.Window.class, w -> assertThat(w.count)
                        .isEqualTo(7));
        assertThat(codec.deserialize(savedChange, Object.class)).isEqualTo(new MovedDesk.RenamedDesk.Seen(3));
    }

    @Test
    @DisplayName("change records nested in an abstract base tasklet are discovered")
    void changesNestedInABaseTaskletAreDiscovered() {
        assertThat(TaskletModel.read(ConcreteStep.class, new ConcreteStep()).kryoTypes())
                .contains(BaseStep.Marked.class, BaseStep.Mark.class);
    }

    @Tasklet.Spec(name = "desk")
    static final class Desk implements Tasklet<String, Desk.Window, Void> {
        static final class Window {
            int count;
        }

        record Seen(int n) implements Change<Window> {
            @Override
            public void applyTo(Window window) {
                window.count += n;
            }
        }

        @Override
        public void run(String input, Ctx<Window, Void> ctx) {}
    }

    abstract static class BaseStep implements Tasklet<String, BaseStep.Mark, Void> {
        static final class Mark {
            boolean marked;
        }

        record Marked() implements Change<Mark> {
            @Override
            public void applyTo(Mark mark) {
                mark.marked = true;
            }
        }

        @Override
        public void run(String input, Ctx<Mark, Void> ctx) {
            ctx.change(new Marked());
        }
    }

    @Tasklet.Spec(name = "concrete_step")
    static final class ConcreteStep extends BaseStep {}

    @Test
    @DisplayName("a listed class Kryo already registers keeps its id and takes no list slot")
    void aListedBuiltinTakesNoSlot() {
        int alone = classIdOf(new KryoCodec(List.of(Window.Level.class)), new Window.Level(1));
        int afterABuiltin =
                classIdOf(new KryoCodec(List.of(java.util.ArrayList.class, Window.Level.class)), new Window.Level(1));
        assertThat(afterABuiltin).isEqualTo(alone);
    }

    private static Map<Class<?>, Integer> windowIds(Class<?>... types) {
        return KryoTypes.ids(List.of(types), Window.class, "window");
    }

    private static KryoCodec windowCodec(Class<?>... types) {
        return KryoCodec.named(windowIds(types));
    }

    /** The class id Kryo writes first: a varint of {@code id + 2}, one byte for a small id. */
    private static int classIdOf(KryoCodec codec, Object value) {
        return (codec.serialize(value)[0] & 0x7f) - 2;
    }

    @Tasklet.Spec(name = "window")
    static final class Window implements Tasklet<String, Window.State, Void> {

        static final class State {
            final List<Level> levels = new ArrayList<>();
            Map<String, Shape> shapes;
            transient Outside notSaved;
        }

        record Level(int depth) {}

        sealed interface Shape permits Shape.Up, Shape.Down {
            record Up() implements Shape {}

            record Down() implements Shape {}
        }

        record Seen(double price) implements Change<State> {
            @Override
            public void applyTo(State state) {}
        }

        record Reset() implements Change<State> {
            @Override
            public void applyTo(State state) {}
        }

        @Override
        public void run(String input, Ctx<State, Void> ctx) {}
    }

    @Tasklet.Spec(name = "no_state")
    static final class NoState implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    /** Two nested change records whose names derive the same id. */
    @Tasklet.Spec(name = "colliding")
    static final class Colliding implements Tasklet<String, Colliding.S, Void> {
        static final class S {}

        record C150() implements Change<S> {
            @Override
            public void applyTo(S state) {}
        }

        record C917() implements Change<S> {
            @Override
            public void applyTo(S state) {}
        }

        @Override
        public void run(String input, Ctx<S, Void> ctx) {}
    }

    record Outside(String value) {}

    record Holder(Object held) {}
}
