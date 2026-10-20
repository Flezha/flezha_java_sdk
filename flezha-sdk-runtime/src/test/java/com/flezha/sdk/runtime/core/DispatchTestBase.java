package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
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
 * helpers and the relay / tasklet fixtures the behaviour classes below it
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
        RelayClassRegistry.reset();
        RelayClassRegistry.reset();
        dispatcher = new ActivationDispatcher();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static Activation work(String factory, int tasklet, Optional<String> key, long changeSeq, WorkItem... items) {
        return new Activation(
                1,
                new CellRef(OP, tasklet, key),
                new ActivationKind.Work(List.of(items)),
                changeSeq,
                factory,
                Optional.empty());
    }

    static WorkItem.Input input(int channel, String payload, long now) {
        return input(channel, payload, now, OptionalLong.empty(), false);
    }

    static WorkItem.Input input(int channel, String payload, long now, OptionalLong offset, boolean replay) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return new WorkItem.Input(
                channel, bytes, List.of(), offset, replay, now, WorkItem.Form.UNTRANSFORMED, List.of());
    }

    /** An inter-tasklet value on channel 1, in the form the core hands it. */
    static WorkItem.Input handoff(byte[] payload, WorkItem.Form form) {
        return new WorkItem.Input(1, payload, List.of(), OptionalLong.empty(), false, 2, form, List.of());
    }

    /** An entry payload on channel 0 with the values its crossing answered for the Cell. */
    static WorkItem.Input prepared(String payload, List<byte[]> values) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return new WorkItem.Input(0, bytes, List.of(), OptionalLong.empty(), false, 1, WorkItem.Form.PREPARED, values);
    }

    /** Every Cell key one prepare answer's values name, in answer order. */
    static List<String> keysAnswered(PrepareResult result) {
        return ((PrepareResult.Ok) result)
                .answer().values().stream()
                        .flatMap(value -> value.keys().stream())
                        .toList();
    }

    static Activation command(String factory, int tasklet, Optional<String> key, ActivationKind kind) {
        return new Activation(1, new CellRef(OP, tasklet, key), kind, 0, factory, Optional.empty());
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
     * A Relay fixture: an id and a wiring, which is all any of these
     * vary. The class stays real (the registry instantiates it by name) and
     * declares only what it is.
     */
    public abstract static class Op implements Relay {
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
     * A stateless fixture Tasklet: a run body, which is all these vary; each
     * subclass names itself with {@code @Tasklet.Spec}. A fixture that needs a
     * state or a change declares itself in full instead.
     */
    public abstract static class Task<O> implements Tasklet<String, Void, O> {

        /** The run body. */
        public interface Body<O> {
            void run(String input, Ctx<Void, O> ctx) throws TaskletException;
        }

        private final Body<O> body;

        protected Task(Body<O> body) {
            this.body = body;
        }

        @Override
        public void run(String input, Ctx<Void, O> ctx) throws TaskletException {
            body.run(input, ctx);
        }
    }

    public static class CounterState {
        public int n;
    }

    public static class CountChange implements Change<CounterState> {
        public int delta;

        public CountChange() {}

        CountChange(int delta) {
            this.delta = delta;
        }

        @Override
        public void applyTo(CounterState state) {
            state.n += delta;
        }
    }

    public static final class CounterRelay extends Op {
        public CounterRelay() {
            super("counter_op", c -> c.input(String.class, "in")
                    .into(CountTask.class)
                    .output("out", s -> ("seen:" + s).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Its change is declared outside it, so it lists its types. */
    @Tasklet.Spec(name = "count_task")
    public static final class CountTask implements Tasklet<String, CounterState, String> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CountChange.class, CounterState.class);
        }

        @Override
        public void run(String input, Ctx<CounterState, String> ctx) throws TaskletException {
            ctx.change(new CountChange(1));
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
    public static final class MigratingCounterRelay extends Op {
        public MigratingCounterRelay() {
            super("migrating_counter_op", c -> c.input(String.class, "in")
                    .into(MigratingCountTask.class)
                    .output("out", s -> ("seen:" + s).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Tasklet.Spec(name = "count_task", schemaVersion = 2, minReadableSchemaVersion = 1)
    public static final class MigratingCountTask implements Tasklet<String, CounterState, String> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CountChange.class, CounterState.class);
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
        public void run(String input, Ctx<CounterState, String> ctx) throws TaskletException {
            ctx.change(new CountChange(1));
            ctx.emit(input);
        }
    }

    /**
     * `output("items_output")` and no `.named(...)` anywhere: the string an
     * author can read in the source is the channel the frame is addressed by.
     */
    public static final class SinkNamesChannelRelay extends Op {
        public SinkNamesChannelRelay() {
            super("sink_names_channel", c -> c.input(String.class, "in")
                    .into(StatelessStateTask.class)
                    .output("items_output", s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class KeyedEmitRelay extends Op {
        public KeyedEmitRelay() {
            super("keyed_op", c -> c.input(String.class, "in")
                    .into(KeyedTask.class)
                    .keyed()
                    .output(s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public static final class StatelessStateRelay extends Op {
        public StatelessStateRelay() {
            super("stateless_state_op", c -> c.input(String.class, "in")
                    .into(StatelessStateTask.class)
                    .output("out", s -> s.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** A tasklet declaring {@code Void} state: it keeps nothing. */
    @Tasklet.Spec(name = "stateless_state_task")
    public static final class StatelessStateTask implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("ok:" + input);
        }
    }

    @Tasklet.Spec(name = "keyed_task")
    public static final class KeyedTask extends Task<String> {
        public KeyedTask() {
            super((input, ctx) -> {
                // The plain spelling: on a keyed channel it carries the Cell's own key.
                if (input.startsWith("plain:")) {
                    ctx.emit(input);
                    return;
                }
                ctx.emitKeyedWithMeta("k-" + input, input, Map.of("user", "meta"));
            });
        }
    }

    public static final class DynamicChainRelay extends Op {
        public DynamicChainRelay() {
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

    public static final class FanOutRelay extends Op {
        public FanOutRelay() {
            super("fan_out", c -> c.input(String.class, "in")
                    .flatMap(String.class, raw -> List.of(raw.split(":")[0] + "@1m", raw.split(":")[0] + "@5m"))
                    .keyBy(slot -> slot)
                    .into(KeyRecorder.class));
        }
    }

    /** Records {@code cellKey=value}. */
    @Tasklet.Spec(name = "key_recorder")
    public static final class KeyRecorder implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {
            RECORDED.add(ctx.cellKey().orElse("") + "=" + input);
        }
    }

    public static final class StaticRelay extends Op {
        public StaticRelay() {
            super("static_op", c -> c.input(String.class, "in").into(StaticTask.class));
        }
    }

    /** Static keys a/b; counts runs per INSTANCE to pin per-Cell instances. */
    @Tasklet.Spec(
            name = "static_task",
            cellKeys = {"a", "b"})
    public static final class StaticTask implements Tasklet<String, Void, Void> {
        private int instanceRuns;

        @Override
        public List<String> cellsFor(String input) {
            return List.of(input.split(":")[0]);
        }

        @Override
        public void run(String input, Ctx<Void, Void> ctx) {
            instanceRuns++;
            RECORDED.add(ctx.cellKey().orElse("") + "#" + instanceRuns + "=" + input);
        }
    }

    public static final class MergeRelay extends Op {
        public MergeRelay() {
            super("merge_op", c -> c.merge(String.class)
                    .from(c.input(String.class, "a"), s -> "A:" + s)
                    .from(c.input(String.class, "b"), s -> "B:" + s)
                    .into(KeyRecorder.class));
        }
    }

    public static final class PipelineRelay extends Op {
        public PipelineRelay() {
            super(
                    "pipeline_op",
                    c -> c.input(String.class, "in").into(MidTask.class).into(KeyRecorder.class));
        }
    }

    @Tasklet.Spec(name = "mid_task")
    public static final class MidTask extends Task<String> {
        public MidTask() {
            super((input, ctx) -> ctx.emit("mid:" + input));
        }
    }

    public static final class MappedPipelineRelay extends Op {
        public MappedPipelineRelay() {
            super("mapped_pipeline_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_mid")
                    .map(String.class, s -> "M:" + s)
                    .into(KeyRecorder.class));
        }
    }

    /** A mapped inter-task edge whose consumer narrows by its own `cellsFor`. */
    public static final class MappedStaticPipelineRelay extends Op {
        public MappedStaticPipelineRelay() {
            super("mapped_static_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_static_mid")
                    .map(String.class, s -> "a:" + s)
                    .into(StaticTask.class));
        }
    }

    /** A mapped inter-task edge that keys the value it yields. */
    public static final class MappedKeyedPipelineRelay extends Op {
        public MappedKeyedPipelineRelay() {
            super("mapped_keyed_op", c -> c.input(String.class, "in")
                    .into(MidTask.class)
                    .named("mapped_keyed_mid")
                    .map(String.class, s -> "K:" + s)
                    .keyBy(shaped -> shaped.split(":")[1])
                    .into(KeyRecorder.class));
        }
    }

    public static final class PoisonRelay extends Op {
        public PoisonRelay() {
            super("poison_op", c -> c.input(String.class, "in").into(PoisonTask.class));
        }
    }

    @Tasklet.Spec(name = "poison_task")
    public static final class PoisonTask extends Task<Void> {
        public PoisonTask() {
            super((input, ctx) -> {
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

    public static final class TypedEntryRelay extends Op {
        public TypedEntryRelay() {
            super("typed_entry_op", c -> c.input(Ticket.class, "in").into(TypedEntryTask.class));
        }
    }

    @Tasklet.Spec(name = "typed_entry_task")
    public static final class TypedEntryTask implements Tasklet<Ticket, Void, Void> {
        @Override
        public void run(Ticket input, Ctx<Void, Void> ctx) throws TaskletException {
            if (input.text().equals("boom")) {
                throw new IllegalStateException("the tasklet failed on a readable ticket");
            }
            RECORDED.add(input.text());
        }
    }

    public static final class TimerRelay extends Op {
        public TimerRelay() {
            super("timer_op", c -> c.input(String.class, "in").into(TimerTask.class));
        }
    }

    @Tasklet.Spec(name = "timer_task")
    public static final class TimerTask implements Tasklet<String, Void, Void> {
        @Override
        public long eventTime(String input) {
            return Long.parseLong(input);
        }

        @Override
        public void run(String input, Ctx<Void, Void> ctx) throws TaskletException {
            ctx.timerAtEventTime(ctx.now() + 10);
            ctx.timerAfter(Duration.ofMillis(5));
        }

        @Override
        public void onTimer(TimerFiring firing, Ctx<Void, Void> ctx) throws TaskletException {
            TIMER_EVENTS.add(firing.kind() + "@" + firing.scheduledAt() + " now=" + ctx.now());
            if (firing.kind() == TimerKind.EVENT_TIME) {
                ctx.timerAfter(Duration.ofMillis(7));
            }
        }
    }

    public static final class SetThenCancelRelay extends Op {
        public SetThenCancelRelay() {
            super("set_then_cancel_op", c -> c.input(String.class, "in").into(SetThenCancelTask.class));
        }
    }

    /**
     * {@code set} registers a timer a second after now; {@code cancel} cancels that one, and
     * {@code cancel-event} the event-time timer at the same timestamp.
     */
    @Tasklet.Spec(name = "set_then_cancel_task")
    public static final class SetThenCancelTask implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) throws TaskletException {
            if (input.equals("set")) {
                ctx.timerAfter(Duration.ofSeconds(1));
            } else {
                TimerKind kind = input.equals("cancel-event") ? TimerKind.EVENT_TIME : TimerKind.PROCESSING_TIME;
                ctx.cancelTimer(kind, ctx.now() + 1_000);
            }
        }

        @Override
        public void onTimer(TimerFiring firing, Ctx<Void, Void> ctx) {}
    }

    public static final class LeafEmitRelay extends Op {
        public LeafEmitRelay() {
            super("leaf_emit_op", c -> c.input(String.class, "in").into(LeafEmitTask.class));
        }
    }

    /** Emits although nothing is wired after it, so it owns no output channel. */
    @Tasklet.Spec(name = "leaf_emit_task")
    public static final class LeafEmitTask extends Task<String> {
        public LeafEmitTask() {
            super((input, ctx) -> ctx.emit(input));
        }
    }

    public static final class CheckpointRelay extends Op {
        public CheckpointRelay() {
            super("ckpt_op", c -> c.input(String.class, "in")
                    .into(CkptTask.class)
                    .keyed()
                    .output(s -> s.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce().withAck(new Ack.Checkpoint()));
        }
    }

    /**
     * The tier IS durable state, so a checkpointing class journals something:
     * this Tasklet declares a state, which is what makes the class
     * {@code persistent} in the record.
     */
    @Tasklet.Spec(name = "ckpt_task")
    public static final class CkptTask implements Tasklet<String, CkptState, String> {
        @Override
        public void run(String input, Ctx<CkptState, String> ctx) throws TaskletException {
            ctx.emitKeyed("ck", input);
            if (input.equals("commit")) {
                ctx.checkpoint();
            }
        }
    }

    /** The checkpoint fixture's state, which it never changes. */
    public static final class CkptState {}

    public static final class ReplayProbeRelay extends Op {
        public ReplayProbeRelay() {
            super("replay_probe", c -> c.input(String.class, "in").into(ReplayProbeTask.class));
        }
    }

    @Tasklet.Spec(name = "replay_probe_task")
    public static final class ReplayProbeTask extends Task<Void> {
        public ReplayProbeTask() {
            super((input, ctx) -> RECORDED.add("replay=" + ctx.replay()));
        }
    }

    /** Completes twice, the later call with its input; on {@code fail} it then throws. */
    @Tasklet.Spec(name = "completing_task")
    public static final class CompletingTask extends Task<Void> {
        public CompletingTask() {
            super((input, ctx) -> {
                ctx.complete("superseded");
                ctx.complete("done:" + input);
                if (input.equals("fail")) {
                    throw new TaskletException("failed after completing");
                }
            });
        }
    }

    public static final class CompletingRelay extends Op {
        public CompletingRelay() {
            super("completing_flow", c -> c.input(String.class, "in").into(CompletingTask.class));
        }
    }

    public static final class CompletingSteps implements Relay {
        @Override
        public String factoryId() {
            return "completing_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(CompletingTask.class);
        }
    }

    /**
     * A structured steps relay whose producing channel `steps_fan` has TWO consumers,
     * one of them behind a map — the shape the fan-in diamond's root has, and
     * the one a per-consumer producer-side emit multiplies.
     */
    public static final class FanOutSteps implements Relay {
        @Override
        public String factoryId() {
            return "fanout_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            var fan = c.input(String.class, "seed").into(MidTask.class).named("steps_fan");
            fan.map(String.class, s -> "mapped:" + s).into(KeyRecorder.class);
            fan.into(KeyRecorder.class, "steps_plain_leaf");
        }
    }

    public static final class SagaSteps implements Relay {
        @Override
        public String factoryId() {
            return "saga_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(UndoableTask.class).into(KeyRecorder.class);
        }
    }

    @Tasklet.Spec(name = "undoable_task")
    public static final class UndoableTask extends Task<String> {
        public UndoableTask() {
            super((input, ctx) -> ctx.emit("fwd:" + input));
        }

        @Override
        public void compensate(List<String> completedOutput, Ctx<Void, String> ctx) throws TaskletException {
            for (String output : completedOutput) {
                RECORDED.add("compensated:" + output);
                ctx.emit("undo:" + output);
            }
        }
    }
}
