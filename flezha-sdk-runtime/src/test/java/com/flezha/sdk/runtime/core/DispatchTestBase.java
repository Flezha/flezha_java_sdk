package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.runtime.operative.StatelessTestTasklet;
import com.flezha.sdk.runtime.operative.StreamOperativeClassRegistry;
import com.flezha.sdk.runtime.workflow.WorkflowOperativeClassRegistry;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;

/**
 * The pure host layer, driven entirely through
 * {@link ActivationDispatcher#dispatch}: constructed Activations in, asserted
 * ActivationResults out — no native core, no wire. This base owns the driving
 * helpers and the operative / tasklet fixtures the behaviour classes below it
 * share; each subclass owns one behaviour of the host.
 */
abstract class DispatchTestBase {

    static final String OP = "op-inst";

    static final List<String> RECORDED = new CopyOnWriteArrayList<>();
    static final AtomicInteger TRAVERSALS = new AtomicInteger();
    static final List<String> TIMER_EVENTS = new CopyOnWriteArrayList<>();

    /** Arms the refusing fixtures' constructors after their definition-time construction succeeded. */
    static final AtomicBoolean REFUSE = new AtomicBoolean();

    static final String REFUSAL = "the author's own refusal";

    ActivationDispatcher dispatcher;

    @BeforeEach
    void reset() {
        RECORDED.clear();
        TRAVERSALS.set(0);
        TIMER_EVENTS.clear();
        REFUSE.set(false);
        StreamOperativeClassRegistry.reset();
        WorkflowOperativeClassRegistry.reset();
        dispatcher = new ActivationDispatcher();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static Activation work(String factory, int tasklet, Optional<String> key, long changeSeq, WorkItem... items) {
        return new Activation(
                1, new CellRef(OP, tasklet, key), new ActivationKind.Work(List.of(items)), changeSeq, factory);
    }

    static WorkItem.Input input(int channel, String payload, long now) {
        return input(channel, payload, now, OptionalLong.empty(), false);
    }

    static WorkItem.Input input(int channel, String payload, long now, OptionalLong offset, boolean replay) {
        return new WorkItem.Input(channel, payload.getBytes(StandardCharsets.UTF_8), List.of(), offset, replay, now);
    }

    static Activation command(String factory, int tasklet, Optional<String> key, ActivationKind kind) {
        return new Activation(1, new CellRef(OP, tasklet, key), kind, 0, factory);
    }

    ActivationOutcome outcome(Activation activation) {
        ActivationResult result = dispatcher.dispatch(activation);
        assertThat(result.token()).isEqualTo(activation.token());
        return result.outcome();
    }

    ItemEffect soleEffect(Activation activation) {
        ActivationOutcome out = outcome(activation);
        assertThat(out).isInstanceOf(ActivationOutcome.Work.class);
        List<ItemEffect> effects = ((ActivationOutcome.Work) out).effects();
        assertThat(effects).hasSize(1);
        return effects.get(0);
    }

    static Map<String, String> metaMap(Output output) {
        return output.metadata().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    /**
     * A StreamOperative fixture: an id and a wiring, which is all any of these
     * vary. The class stays real (the registry instantiates it by name) and
     * declares only what it is.
     */
    public abstract static class Op implements StreamOperative {
        private final String factoryId;
        private final Consumer<ChannelBuilder> wiring;

        protected Op(String factoryId, Consumer<ChannelBuilder> wiring) {
            this.factoryId = factoryId;
            this.wiring = wiring;
        }

        @Override
        public String factoryId() {
            return factoryId;
        }

        @Override
        public void channels(ChannelBuilder c) {
            wiring.accept(c);
        }
    }

    /**
     * A stateless fixture Tasklet over an {@code Object} state: a name and a
     * run body, which is all these vary. A fixture that needs the cell key,
     * a state type of its own or a Change declares itself in full instead.
     */
    public abstract static class Task<O> extends StatelessTestTasklet<String, Object, O> {

        /** The run body, with the state handle the fixtures never read elided. */
        public interface Body {
            void run(String input, TaskletCtx ctx) throws TaskletException;
        }

        private final String name;
        private final Body body;

        protected Task(String name, Body body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {
            body.run(input, ctx);
        }
    }

    public static class CounterState {
        public int n;
    }

    public static class CountChange implements Change {
        public int delta;

        public CountChange() {}

        CountChange(int delta) {
            this.delta = delta;
        }
    }

    public static final class CounterOperative extends Op {
        public CounterOperative() {
            super("counter_op", c -> c.input(String.class, "in")
                    .into(CountTask.class)
                    .sink("out", s -> ("seen:" + s).getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class CountTask implements Tasklet<String, CounterState, String, CountChange, WallClock, Void> {
        @Override
        public String name() {
            return "count_task";
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CountChange.class, CounterState.class);
        }

        @Override
        public CounterState initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new CounterState();
        }

        @Override
        public void applyChange(CounterState state, CountChange change) {
            state.n += change.delta;
        }

        @Override
        public void run(String input, StateHandle<CounterState, CountChange> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new CountChange(1));
            if (input.startsWith("key:")) {
                ctx.emitKeyed("k", input);
            } else {
                ctx.emit(input);
            }
        }
    }

    /**
     * The Counter class one deploy later: version 2, reading down to 1 through
     * declared migrations. The v1 encoding here is deliberately trivial — what
     * the test proves is that the runtime routes an older body to the
     * migration instead of to the codec.
     */
    public static final class MigratingCounterOperative extends Op {
        public MigratingCounterOperative() {
            super("migrating_counter_op", c -> c.input(String.class, "in")
                    .into(MigratingCountTask.class)
                    .sink("out", s -> ("seen:" + s).getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class MigratingCountTask
            implements Tasklet<String, CounterState, String, CountChange, WallClock, Void> {
        @Override
        public String name() {
            return "count_task";
        }

        @Override
        public int schemaVersion() {
            return 2;
        }

        @Override
        public int minReadableSchemaVersion() {
            return 1;
        }

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CountChange.class, CounterState.class);
        }

        @Override
        public CounterState initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new CounterState();
        }

        @Override
        public CounterState migrateState(int fromVersion, byte[] bytes) {
            if (fromVersion != 1) {
                throw new IllegalStateException("no migration from " + fromVersion);
            }
            CounterState migrated = new CounterState();
            migrated.n = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8));
            return migrated;
        }

        @Override
        public CountChange migrateChange(int fromVersion, byte[] bytes) {
            if (fromVersion != 1) {
                throw new IllegalStateException("no migration from " + fromVersion);
            }
            return new CountChange(Integer.parseInt(new String(bytes, StandardCharsets.UTF_8)));
        }

        @Override
        public void applyChange(CounterState state, CountChange change) {
            state.n += change.delta;
        }

        @Override
        public void run(String input, StateHandle<CounterState, CountChange> state, TaskletCtx ctx)
                throws TaskletException {
            state.apply(new CountChange(1));
            ctx.emit(input);
        }
    }

    /**
     * `sink("items_output")` and no `.named(...)` anywhere: the string an
     * author can read in the source is the channel the frame is addressed by.
     */
    public static final class SinkNamesChannelOperative extends Op {
        public SinkNamesChannelOperative() {
            super("sink_names_channel", c -> c.input(String.class, "in")
                    .into(StatelessStateTask.class)
                    .sink("items_output", s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class KeyedEmitOperative extends Op {
        public KeyedEmitOperative() {
            super("keyed_op", c -> c.input(String.class, "in")
                    .into(KeyedTask.class)
                    .keyed()
                    .sink(s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class StatelessStateOperative extends Op {
        public StatelessStateOperative() {
            super("stateless_state_op", c -> c.input(String.class, "in")
                    .into(StatelessStateTask.class)
                    .sink("out", s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** The shape the annotation processor generates for a stateless `@Tasklet`. */
    public static final class StatelessStateTask extends StatelessTestTasklet<String, Stateless, String> {
        @Override
        public String name() {
            return "stateless_state_task";
        }

        @Override
        protected Stateless initState(Optional<String> cellKey) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.emit("ok:" + input);
        }
    }

    public static final class KeyedTask extends Task<String> {
        public KeyedTask() {
            super("keyed_task", (input, ctx) -> {
                // The plain spelling: on a keyed channel it carries the Cell's own key.
                if (input.startsWith("plain:")) {
                    ctx.emit(input);
                    return;
                }
                ctx.emitKeyedWithMeta("k-" + input, input, Map.of("user", "meta"));
            });
        }
    }

    public static final class DynamicChainOperative extends Op {
        public DynamicChainOperative() {
            super("dyn_chain", c -> c.input(String.class, "in")
                    .filter(raw -> {
                        TRAVERSALS.incrementAndGet();
                        return !raw.startsWith("#");
                    })
                    .map(String.class, raw -> raw.split(":")[0] + "x" + raw.split(":")[1])
                    .keyBy(shaped -> shaped.split("x")[0])
                    .into(KeyRecorder.class));
        }
    }

    public static final class FanOutOperative extends Op {
        public FanOutOperative() {
            super("fan_out", c -> c.input(String.class, "in")
                    .flatMap(String.class, raw -> List.of(raw.split(":")[0] + "@1m", raw.split(":")[0] + "@5m"))
                    .keyBy(slot -> slot)
                    .into(KeyRecorder.class));
        }
    }

    /** Records {@code cellKey=value}; one instance per Cell by contract. */
    public static final class KeyRecorder extends StatelessTestTasklet<String, Object, Void> {
        private String key = "";

        @Override
        public String name() {
            return "key_recorder";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            this.key = cellKey.orElse("");
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {
            RECORDED.add(key + "=" + input);
        }
    }

    public static final class StaticOperative extends Op {
        public StaticOperative() {
            super("static_op", c -> c.input(String.class, "in").into(StaticTask.class));
        }
    }

    /** Static keys a/b; counts runs per INSTANCE to pin per-Cell instances. */
    public static final class StaticTask extends StatelessTestTasklet<String, Object, Void> {
        private String key = "";
        private int instanceRuns;

        @Override
        public String name() {
            return "static_task";
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b");
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of(input.split(":")[0]);
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            this.key = cellKey.orElse("");
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {
            instanceRuns++;
            RECORDED.add(key + "#" + instanceRuns + "=" + input);
        }
    }

    public static final class MergeOperative extends Op {
        public MergeOperative() {
            super("merge_op", c -> c.merge(String.class)
                    .from(c.input(String.class, "a"), s -> "A:" + s)
                    .from(c.input(String.class, "b"), s -> "B:" + s)
                    .into(KeyRecorder.class));
        }
    }

    public static final class PipelineOperative extends Op {
        public PipelineOperative() {
            super(
                    "pipeline_op",
                    c -> c.input(String.class, "in").into(MidTask.class).into(KeyRecorder.class));
        }
    }

    public static final class MidTask extends Task<String> {
        public MidTask() {
            super("mid_task", (input, ctx) -> ctx.emit("mid:" + input));
        }
    }

    public static final class MappedPipelineOperative extends Op {
        public MappedPipelineOperative() {
            super("mapped_pipeline_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_mid")
                    .map(String.class, s -> "M:" + s)
                    .into(KeyRecorder.class));
        }
    }

    /** A mapped inter-task edge whose consumer narrows by its own `cellsFor`. */
    public static final class MappedStaticPipelineOperative extends Op {
        public MappedStaticPipelineOperative() {
            super("mapped_static_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_static_mid")
                    .map(String.class, s -> "a:" + s)
                    .into(StaticTask.class));
        }
    }

    /** A mapped inter-task edge that keys the value it yields. */
    public static final class MappedKeyedPipelineOperative extends Op {
        public MappedKeyedPipelineOperative() {
            super("mapped_keyed_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_keyed_mid")
                    .map(String.class, s -> "K:" + s)
                    .keyBy(shaped -> shaped.split(":")[1])
                    .into(KeyRecorder.class));
        }
    }

    public static final class PoisonOperative extends Op {
        public PoisonOperative() {
            super("poison_op", c -> c.input(String.class, "in").into(PoisonTask.class));
        }
    }

    public static final class PoisonTask extends Task<Void> {
        public PoisonTask() {
            super("poison_task", (input, ctx) -> {
                if (input.equals("bad-fatal")) {
                    throw new TaskletException.NonRetryable("fatal poison");
                }
                if (input.startsWith("bad")) {
                    throw new IllegalStateException("transient poison");
                }
                RECORDED.add(input);
            });
        }
    }

    /** A JSON-typed entry: what the default entry decoder rejects is the seam's to classify. */
    public record Ticket(String text) {}

    public static final class TypedEntryOperative extends Op {
        public TypedEntryOperative() {
            super("typed_entry_op", c -> c.input(Ticket.class, "in").into(TypedEntryTask.class));
        }
    }

    public static final class TypedEntryTask implements Tasklet<Ticket, Object, Void, NoChange, WallClock, Void> {
        @Override
        public String name() {
            return "typed_entry_task";
        }

        @Override
        public Object initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Object();
        }

        @Override
        public void run(Ticket input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {
            if (input.text().equals("boom")) {
                throw new IllegalStateException("the tasklet failed on a readable ticket");
            }
            RECORDED.add(input.text());
        }
    }

    public static final class TimerOperative extends Op {
        public TimerOperative() {
            super("timer_op", c -> c.input(String.class, "in").into(TimerTask.class));
        }
    }

    public static final class TimerTask implements Tasklet<String, Object, Void, NoChange, EventTimeFrom, Void> {
        @Override
        public String name() {
            return "timer_task";
        }

        @Override
        public long eventTime(String input) {
            return Long.parseLong(input);
        }

        @Override
        public Object initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.timerAtEventTime(ctx.now() + 10);
            ctx.timerAfter(Duration.ofMillis(5));
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, StateHandle<Object, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            TIMER_EVENTS.add(kind + "@" + firedAt + " now=" + ctx.now());
            if (kind == TimerKind.EVENT_TIME) {
                ctx.timerAfter(Duration.ofMillis(7));
            }
        }
    }

    public static final class WallTimerOperative extends Op {
        public WallTimerOperative() {
            super("wall_timer_op", c -> c.input(String.class, "in").into(WallTimerTask.class));
        }
    }

    public static final class WallTimerTask extends Task<Void> {
        public WallTimerTask() {
            super("wall_timer_task", (input, ctx) -> ctx.timerAtEventTime(1));
        }
    }

    public static final class CheckpointOperative extends Op {
        public CheckpointOperative() {
            super("ckpt_op", c -> {
                c.effectivelyOnce();
                c.checkpointAck();
                c.input(String.class, "in").into(CkptTask.class).keyed().sink(s -> s.getBytes(StandardCharsets.UTF_8));
            });
        }
    }

    /**
     * The tier IS durable state, so a checkpointing class journals something:
     * this Tasklet declares a Change, which is what makes the class
     * {@code persistent} in the record.
     */
    public static final class CkptTask implements Tasklet<String, Object, String, CkptChange, WallClock, Void> {
        @Override
        public String name() {
            return "ckpt_task";
        }

        @Override
        public Object initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Object();
        }

        @Override
        public void applyChange(Object state, CkptChange change) {}

        @Override
        public void run(String input, StateHandle<Object, CkptChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.emitKeyed("ck", input);
            if (input.equals("commit")) {
                ctx.checkpoint();
            }
        }
    }

    /** The one Change the checkpoint fixture journals. */
    public record CkptChange(String value) implements Change {}

    public static final class ReplayProbeOperative extends Op {
        public ReplayProbeOperative() {
            super("replay_probe", c -> c.input(String.class, "in").into(ReplayProbeTask.class));
        }
    }

    public static final class ReplayProbeTask extends Task<Void> {
        public ReplayProbeTask() {
            super("replay_probe_task", (input, ctx) -> RECORDED.add("replaying=" + ctx.replaying()));
        }
    }

    /** Completes twice, the later call with its input; on {@code fail} it then throws. */
    public static final class CompletingTask extends Task<Void> {
        public CompletingTask() {
            super("completing_task", (input, ctx) -> {
                ctx.complete("superseded");
                ctx.complete("done:" + input);
                if (input.equals("fail")) {
                    throw new TaskletException("failed after completing");
                }
            });
        }
    }

    public static final class CompletingStreamOperative extends Op {
        public CompletingStreamOperative() {
            super("completing_stream", c -> c.input(String.class, "in").into(CompletingTask.class));
        }
    }

    public static final class CompletingWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "completing_workflow";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(CompletingTask.class);
        }
    }

    /**
     * A structured workflow whose producing channel `wf_fan` has TWO consumers,
     * one of them behind a map — the shape the fan-in diamond's root has, and
     * the one a per-consumer producer-side emit multiplies.
     */
    public static final class FanOutWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "fanout_workflow";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            var fan = c.input(String.class, "seed").into(MidTask.class).named("wf_fan");
            fan.map(String.class, s -> "mapped:" + s).into(KeyRecorder.class);
            fan.into(KeyRecorder.class, "wf_plain_leaf");
        }
    }

    public static final class SagaWorkflow implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "saga_workflow";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(UndoableTask.class).into(KeyRecorder.class);
        }
    }

    public static final class UndoableTask extends Task<String> {
        public UndoableTask() {
            super("undoable_task", (input, ctx) -> ctx.emit("fwd:" + input));
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<Object, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            for (String output : completedOutput) {
                RECORDED.add("compensated:" + output);
                ctx.emit("undo:" + output);
            }
        }
    }
}
