package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.internal.ShortFormDeclaration;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.KryoTypes;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What one tasklet class declares, read once at registration: its name and settings from
 * {@link Tasklet.Spec}, its three types from the {@code implements} clause, the hooks it writes,
 * its clock, how a new cell's state is built, and the types its state is encoded with. The
 * runtime and the test harness both read a tasklet through it, so they cannot disagree.
 *
 * <p>Refusals that name a member, a type or a hook are collected in {@link #errors()} for the
 * builder to report together; a class carrying no {@link Tasklet.Spec} is refused at once.
 */
public final class TaskletModel {

    @Tasklet.Spec(name = "")
    private static final class Defaults {}

    private static final Tasklet.Spec DEFAULTS = Defaults.class.getAnnotation(Tasklet.Spec.class);

    private final Class<?> taskletClass;
    private final String name;
    private final Tasklet.Spec spec;
    private final List<FlezhaException> errors = new ArrayList<>();
    private final boolean typesResolved;
    private final Class<?> inputType;
    private final Class<?> stateType;
    private final Class<?> outputType;
    private final ClockKind clock;
    private final boolean cellsForDeclared;
    private final boolean compensateDeclared;
    private final boolean initStateDeclared;
    private final boolean migrates;
    private final boolean kryoTypesListed;
    private final List<Class<?>> kryoTypes;

    @Nullable
    private final Constructor<?> stateConstructor;

    private TaskletModel(Class<?> taskletClass, @Nullable Tasklet<?, ?, ?> probe) {
        this.taskletClass = taskletClass;
        Tasklet.Spec declared = taskletClass.getAnnotation(Tasklet.Spec.class);
        ShortFormDeclaration shortForm = ShortFormDeclaration.of(taskletClass);
        if (declared == null && shortForm == null) {
            throw FlezhaException.of("topology.tasklet_name_missing", Map.of("tasklet", taskletClass.getName()));
        }
        this.spec = declared != null ? declared : DEFAULTS;
        this.name = declared != null ? declared.name() : shortForm.factoryId();
        Class<?>[] types = resolveTypes(taskletClass);
        this.typesResolved = types != null;
        this.inputType = typesResolved ? types[0] : Object.class;
        this.stateType = typesResolved ? types[1] : Void.class;
        this.outputType = typesResolved ? types[2] : Object.class;
        this.cellsForDeclared = Hook.CELLS_FOR.declaredBy(taskletClass);
        this.compensateDeclared = Hook.COMPENSATE.declaredBy(taskletClass);
        this.initStateDeclared = Hook.INIT_STATE.declaredBy(taskletClass);
        this.migrates = Hook.MIGRATE_STATE.declaredBy(taskletClass) || Hook.MIGRATE_CHANGE.declaredBy(taskletClass);
        this.kryoTypesListed = Hook.KRYO_TYPES.declaredBy(taskletClass);
        this.clock = clockOf(Hook.EVENT_TIME.declaredBy(taskletClass));
        this.stateConstructor = stateless() || initStateDeclared ? null : stateConstructor();
        this.kryoTypes = kryoTypes(probe);
        checkSettings();
    }

    /**
     * Read {@code taskletClass}. {@code probe} is an instance of it, used only to read a written
     * {@code kryoTypes()}; {@code null} when the class could not be constructed.
     *
     * @throws FlezhaException {@code topology.tasklet_name_missing} when the class carries no
     *     {@link Tasklet.Spec} and is not a short form
     */
    @Nonnull
    public static TaskletModel read(@Nonnull Class<?> taskletClass, @Nullable Tasklet<?, ?, ?> probe) {
        return new TaskletModel(taskletClass, probe);
    }

    private Class<?>[] resolveTypes(Class<?> cls) {
        try {
            return new Class<?>[] {
                TypeTokens.require(cls, TypeTokens.Token.INPUT),
                TypeTokens.require(cls, TypeTokens.Token.STATE),
                TypeTokens.require(cls, TypeTokens.Token.OUTPUT)
            };
        } catch (FlezhaException e) {
            errors.add(e);
            return null;
        }
    }

    private ClockKind clockOf(boolean eventTimeWritten) {
        if (eventTimeWritten && spec.logicalClock()) {
            errors.add(FlezhaException.of("topology.two_clocks", Map.of("tasklet", name)));
        }
        if (eventTimeWritten) {
            return ClockKind.EVENT_TIME;
        }
        return spec.logicalClock() ? ClockKind.LOGICAL : ClockKind.WALL;
    }

    @Nullable
    private Constructor<?> stateConstructor() {
        if (!Modifier.isAbstract(stateType.getModifiers())) {
            try {
                Constructor<?> constructor = stateType.getDeclaredConstructor();
                if (constructor.trySetAccessible()) {
                    return constructor;
                }
            } catch (NoSuchMethodException e) {
                // Refused below, naming the class.
            }
        }
        errors.add(FlezhaException.of(
                "topology.state_not_constructible", Map.of("tasklet", name, "state", stateType.getName())));
        return null;
    }

    private List<Class<?>> kryoTypes(@Nullable Tasklet<?, ?, ?> probe) {
        if (kryoTypesListed) {
            return probe == null ? List.of() : List.copyOf(probe.kryoTypes());
        }
        if (!typesResolved) {
            return List.of();
        }
        List<Class<?>> discovered = KryoTypes.discover(taskletClass, stateType);
        errors.addAll(KryoTypes.collisions(name, KryoTypes.ids(discovered, taskletClass, name)));
        return discovered;
    }

    private void checkSettings() {
        if (name.isEmpty()) {
            errors.add(FlezhaException.of("topology.tasklet_name_missing", Map.of("tasklet", taskletClass.getName())));
        }
        if (spec.mailboxCapacity() != DEFAULTS.mailboxCapacity()) {
            refuseNegative("mailboxCapacity", spec.mailboxCapacity());
        }
        refuseNegative("maxCells", spec.maxCells());
        refuseNegative("idleTtlSeconds", spec.idleTtlSeconds());
        refuseNegative("maxTimersPerCell", spec.maxTimersPerCell());
        refuseNegative("compactAfterBytes", spec.compactAfterBytes());
        refuseNegative("schemaVersion", spec.schemaVersion());
        refuseNegative("minReadableSchemaVersion", spec.minReadableSchemaVersion());
    }

    private void refuseNegative(String member, long value) {
        if (value < 0) {
            errors.add(FlezhaException.of(
                    "topology.negative_setting", Map.of("tasklet", name, "setting", member, "value", value)));
        }
    }

    /**
     * A new cell's state: {@code null} for a {@code Void} state, the written
     * {@code initState(cellKey)} on {@code tasklet}, or the state class's no-arg constructor.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object initialState(@Nonnull Tasklet tasklet, @Nonnull Optional<String> cellKey) {
        if (stateless()) {
            return null;
        }
        if (initStateDeclared) {
            return tasklet.initState(cellKey);
        }
        if (stateConstructor == null) {
            throw new IllegalStateException("tasklet `" + name + "` has no way to build its state");
        }
        try {
            return RuntimeBridge.construct(stateConstructor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("state constructor of `" + name + "` failed", e);
        }
    }

    /**
     * Records {@code change} for one invocation: applies it to {@code state} and appends it to
     * {@code changes}. The runtime and the test harness both record through here.
     *
     * @throws IllegalStateException for a tasklet whose state is {@code Void}
     */
    public <S> void record(@Nonnull Change<S> change, S state, @Nonnull List<? super Change<S>> changes) {
        if (stateless()) {
            throw new IllegalStateException("tasklet `" + name + "` declares Void state, so it has no state to change");
        }
        change.applyTo(state);
        changes.add(change);
    }

    /** The codec this tasklet's state and changes are encoded with, over {@code types}. */
    @Nonnull
    public KryoCodec codec(@Nonnull List<Class<?>> types) {
        return kryoTypesListed ? new KryoCodec(types) : KryoCodec.named(KryoTypes.ids(types, taskletClass, name));
    }

    /** The refusals reading the class produced; empty when it is sound. */
    @Nonnull
    public List<FlezhaException> errors() {
        return List.copyOf(errors);
    }

    @Nonnull
    public Class<?> taskletClass() {
        return taskletClass;
    }

    /** The tasklet's durable name: its {@link Tasklet.Spec#name()}, or a short form's relay id. */
    @Nonnull
    public String name() {
        return name;
    }

    /** Whether the three type arguments resolved; {@code false} leaves placeholders standing in. */
    public boolean typesResolved() {
        return typesResolved;
    }

    @Nonnull
    public Class<?> inputType() {
        return inputType;
    }

    @Nonnull
    public Class<?> stateType() {
        return stateType;
    }

    @Nonnull
    public Class<?> outputType() {
        return outputType;
    }

    /** A {@code Void} state: nothing is kept, and nothing is journaled. */
    public boolean stateless() {
        return stateType == Void.class;
    }

    @Nonnull
    public ClockKind clock() {
        return clock;
    }

    @Nonnull
    public List<String> cellKeys() {
        return List.of(spec.cellKeys());
    }

    public boolean cellsForDeclared() {
        return cellsForDeclared;
    }

    /** Whether the class writes {@code compensate}, which makes it compensable. */
    public boolean compensateDeclared() {
        return compensateDeclared;
    }

    /** Whether the class writes a migration, so a schema floor below its version can be read up. */
    public boolean migrates() {
        return migrates;
    }

    /** The declared mailbox capacity; empty takes the environment's or the default. */
    @Nonnull
    public OptionalInt mailboxCapacity() {
        return spec.mailboxCapacity() == DEFAULTS.mailboxCapacity()
                ? OptionalInt.empty()
                : OptionalInt.of(spec.mailboxCapacity());
    }

    /** The declared dynamic-cell cap; empty when it is the default. */
    @Nonnull
    public OptionalLong maxCells() {
        return spec.maxCells() == DEFAULTS.maxCells() ? OptionalLong.empty() : OptionalLong.of(spec.maxCells());
    }

    /** The idle TTL of a dynamic cell; empty keeps every cell. */
    @Nonnull
    public Optional<Duration> cellIdleTtl() {
        return spec.idleTtlSeconds() == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(spec.idleTtlSeconds()));
    }

    public int maxTimersPerCell() {
        return spec.maxTimersPerCell();
    }

    public long compactAfterBytes() {
        return spec.compactAfterBytes();
    }

    public int schemaVersion() {
        return spec.schemaVersion();
    }

    public int minReadableSchemaVersion() {
        return spec.minReadableSchemaVersion() == 0 ? spec.schemaVersion() : spec.minReadableSchemaVersion();
    }

    /** Whether the class writes {@code kryoTypes()}, and so owns its ids in list order. */
    public boolean kryoTypesListed() {
        return kryoTypesListed;
    }

    /** The types the state and changes are encoded with, listed or discovered. */
    @Nonnull
    public List<Class<?>> kryoTypes() {
        return kryoTypes;
    }

    /** The optional {@link Tasklet} hooks a class declares by writing them, each by its erased signature. */
    enum Hook {
        COMPENSATE("compensate", List.class, Ctx.class),
        CELLS_FOR("cellsFor", Object.class),
        EVENT_TIME("eventTime", Object.class),
        INIT_STATE("initState", Optional.class),
        MIGRATE_STATE("migrateState", int.class, byte[].class),
        MIGRATE_CHANGE("migrateChange", int.class, byte[].class),
        KRYO_TYPES("kryoTypes");

        private final String method;
        private final Class<?>[] erased;

        Hook(String method, Class<?>... erased) {
            this.method = method;
            this.erased = erased;
        }

        /**
         * Whether {@code impl}, or any class above it, writes this hook rather than inheriting the
         * interface's default. A hook taking {@code Input} erases to {@code Object}, and an author
         * writing a concrete parameter type gets a compiler bridge on their own class, so bridges
         * are what answer {@code true} there and are not filtered out.
         */
        boolean declaredBy(Class<?> impl) {
            try {
                return impl.getMethod(method, erased).getDeclaringClass() != Tasklet.class;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Tasklet declares no hook `" + method + "`", e);
            }
        }
    }
}
