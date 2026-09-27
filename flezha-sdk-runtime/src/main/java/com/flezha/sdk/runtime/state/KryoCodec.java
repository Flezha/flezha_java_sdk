package com.flezha.sdk.runtime.state;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.flezha.sdk.state.CoalesceKey;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateSerializeException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/**
 * Kryo-backed serializer for per-Cell state and per-Cell Change
 * payloads, one per tasklet. Its user types are the ones the tasklet lists in
 * {@code kryoTypes()}, registered in list order, or the ones {@link KryoTypes}
 * discovers, each under the id it derives from the type's name.
 *
 * <p>Stable type ids are what let a saved record decode as the class that
 * wrote it: a new change type must never move the id of an existing one.
 *
 * <p>The encode buffer is reused per thread rather than allocated per value,
 * which makes it shared mutable state for the duration of one encode. Two
 * rules follow and neither is visible at a call site: the finished bytes are
 * copied out, because the next encode on this thread overwrites the buffer;
 * and an encode entered while another encode is in progress on this thread is
 * refused rather than allowed to write into the buffer it is nested inside.
 */
public final class KryoCodec {

    /** The buffer a thread starts with, and returns to after an outsized value. */
    private static final int INITIAL_CAPACITY = 4096;

    /**
     * The capacity a thread may keep between encodes. Above it the buffer is
     * handed back, so one outsized value cannot pin its size on that thread for
     * the life of the process.
     */
    private static final int TRIM_ABOVE = 64 * 1024;

    /** What a finished decode points its reader at, so it holds no payload. */
    private static final byte[] NO_BYTES = new byte[0];

    private final ThreadLocal<Locals> locals;
    private final List<Class<?>> registeredTypes;

    /**
     * Everything one thread owns: its Kryo, its encode buffer, its reader, and
     * the flags that keep one encode out of another's buffer. One holder rather
     * than one per part, so a call costs one thread-local lookup.
     *
     * <p>The unbounded growth is what the discarded {@code Output(bos)} had: a
     * 4 KB window flushing into an unbounded operator that accepted any size. {@code Input}
     * starts uninitialized; every decode sets its buffer before reading.
     */
    private static final class Locals {
        private final Kryo kryo;
        private final Output output = new Output(INITIAL_CAPACITY, -1);
        private final Input input = new Input();
        private boolean encoding;
        private boolean decoding;

        private Locals(Kryo kryo) {
            this.kryo = kryo;
        }
    }

    /** A codec registering {@code userTypes} after the built-ins, in list order. */
    public KryoCodec(@Nonnull List<Class<?>> userTypes) {
        this(userTypes, true, null);
    }

    /**
     * {@code requireRegistration = false} builds a class-name-tagged codec for
     * transient in-process values (edge payloads crossing the poll core), where
     * arbitrary user types must encode without a declared id table.
     */
    public KryoCodec(@Nonnull List<Class<?>> userTypes, boolean requireRegistration) {
        this(userTypes, requireRegistration, null);
    }

    /** A codec registering each type under the id given beside it, as {@link KryoTypes#ids} derives them. */
    @Nonnull
    public static KryoCodec named(@Nonnull Map<Class<?>, Integer> idsByType) {
        return new KryoCodec(List.copyOf(idsByType.keySet()), true, Map.copyOf(idsByType));
    }

    private KryoCodec(List<Class<?>> userTypes, boolean requireRegistration, Map<Class<?>, Integer> idsByType) {
        this.registeredTypes = List.copyOf(userTypes);
        this.locals = ThreadLocal.withInitial(() -> {
            Kryo kryo = new Kryo();
            kryo.setRegistrationRequired(requireRegistration);
            int next = FIRST_BUILTIN_ID;
            for (Class<?> c : BUILTINS) {
                if (kryo.getClassResolver().getRegistration(c) == null) {
                    kryo.register(c, next++);
                }
            }
            for (Class<?> c : registeredTypes) {
                if (idsByType != null) {
                    kryo.register(c, idsByType.get(c));
                } else if (kryo.getClassResolver().getRegistration(c) == null) {
                    kryo.register(c, next++);
                }
            }
            return new Locals(kryo);
        });
    }

    /** Whether {@code type} is one of the built-in registrations every codec carries. */
    public static boolean isBuiltin(@Nonnull Class<?> type) {
        return BUILTINS.contains(type);
    }

    /**
     * The first built-in id. Kryo's own defaults (the primitives and
     * {@code String}) hold 0 to 8, and 9 is left unused so every built-in keeps
     * the id saved records carry.
     */
    private static final int FIRST_BUILTIN_ID = 10;

    /**
     * Built-in registrations, in id order from {@link #FIRST_BUILTIN_ID}; a
     * class Kryo already registers by default keeps its default id. A codec's
     * listed user types take the ids after them in list order, except one
     * already registered, which keeps its id and takes no slot; derived ids sit
     * in a band above them. Append only: an insertion moves every later id.
     */
    private static final List<Class<?>> BUILTINS = List.of(
            RestoreInitPayload.class,
            CoalesceKey.class,
            byte[].class,
            int[].class,
            long[].class,
            double[].class,
            String.class,
            Long.class,
            Integer.class,
            Double.class,
            Float.class,
            Boolean.class,
            Optional.class,
            java.util.ArrayList.class,
            java.util.LinkedList.class,
            HashMap.class,
            LinkedHashMap.class,
            TreeMap.class,
            HashSet.class,
            LinkedHashSet.class,
            TreeSet.class,
            List.class,
            Map.class,
            Set.class,
            java.time.Instant.class,
            java.util.ArrayDeque.class,
            java.util.Deque.class);

    /**
     * Serialize an arbitrary registered object into this thread's buffer and
     * return a copy of what was written.
     *
     * <p>The copy is what transfers ownership: the caller keeps the array while
     * the next encode on this thread overwrites the buffer behind it.
     */
    @Nonnull
    public byte[] serialize(@Nonnull Object obj) {
        Locals mine = locals.get();
        if (mine.encoding) {
            throw new IllegalStateException(
                    reentered("serialize", obj.getClass().getName()));
        }
        mine.encoding = true;
        try {
            mine.output.reset();
            mine.kryo.writeClassAndObject(mine.output, obj);
            return mine.output.toBytes();
        } catch (IllegalArgumentException | KryoException e) {
            throw unregistered(e);
        } finally {
            mine.encoding = false;
            if (mine.output.getBuffer().length > TRIM_ABOVE) {
                mine.output.setBuffer(new byte[INITIAL_CAPACITY], -1);
            }
        }
    }

    /** Serialize each of {@code values}, in order: how a cell's recorded changes are flushed. */
    @Nonnull
    public List<byte[]> serializeAll(@Nonnull List<?> values) {
        List<byte[]> out = new ArrayList<>(values.size());
        for (Object value : values) {
            out.add(serialize(value));
        }
        return out;
    }

    /** Deserialize bytes to the requested type. */
    @Nonnull
    public <T> T deserialize(@Nonnull byte[] bytes, @Nonnull Class<T> type) {
        Locals mine = locals.get();
        if (mine.decoding) {
            throw new IllegalStateException(reentered("deserialize", type.getName()));
        }
        mine.decoding = true;
        try {
            mine.input.setBuffer(bytes);
            return type.cast(mine.kryo.readClassAndObject(mine.input));
        } finally {
            mine.decoding = false;
            mine.input.setBuffer(NO_BYTES);
        }
    }

    /**
     * Kryo refuses an unregistered class with an {@link IllegalArgumentException}, wrapped in a
     * {@link KryoException} when the class sits in a field. That refusal is named, with its fixes;
     * any other failure is rethrown as it was.
     */
    private static RuntimeException unregistered(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (t instanceof IllegalArgumentException && message != null && message.startsWith(NOT_REGISTERED)) {
                int end = message.indexOf('\n');
                String name = message.substring(NOT_REGISTERED.length(), end < 0 ? message.length() : end)
                        .trim();
                return new StateSerializeException.UnregisteredClass(name, e);
            }
        }
        return e;
    }

    private static final String NOT_REGISTERED = "Class is not registered:";

    private static String reentered(String method, String type) {
        return "KryoCodec."
                + method
                + " re-entered on one thread for "
                + type
                + ": this codec reuses one buffer per thread, so a serializer that calls back "
                + "into it would write into the buffer it is nested inside. Give the nested "
                + "value its own codec instead.";
    }

    /** Aggregated registered user-type list (copy). */
    @Nonnull
    public List<Class<?>> registeredTypes() {
        return registeredTypes;
    }
}
