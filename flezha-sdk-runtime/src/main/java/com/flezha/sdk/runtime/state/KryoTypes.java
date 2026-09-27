package com.flezha.sdk.runtime.state;

import com.flezha.sdk.state.Change;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * The user types a tasklet's state and changes are encoded with, found by walking its classes, and
 * the id each one takes, derived from its name so adding a type never moves the id of another.
 *
 * <p>A type nested in the tasklet class is named by the tasklet's durable name and its path inside
 * the class ({@code track_prices$PriceWindow}), so renaming or moving the tasklet class keeps every
 * id its saved records carry. Any other type is named by its binary class name.
 */
public final class KryoTypes {

    /** Derived ids start above every built-in registration. */
    private static final int BAND_BASE = 1024;

    private static final int BAND_MASK = (1 << 20) - 1;

    private KryoTypes() {}

    /**
     * The id each of {@code types} registers under when tasklet {@code taskletName}, declared by
     * {@code taskletClass}, does not list its own types; in the order given.
     */
    @Nonnull
    public static Map<Class<?>, Integer> ids(
            @Nonnull List<Class<?>> types, @Nonnull Class<?> taskletClass, @Nonnull String taskletName) {
        Map<Class<?>, Integer> ids = new LinkedHashMap<>();
        for (Class<?> type : types) {
            ids.put(type, idOf(nameOf(type, taskletClass, taskletName)));
        }
        return ids;
    }

    /** The name a type's id derives from: relative to the tasklet when nested in it. */
    private static String nameOf(Class<?> type, Class<?> taskletClass, String taskletName) {
        for (Class<?> outer = type.getEnclosingClass(); outer != null; outer = outer.getEnclosingClass()) {
            if (outer == taskletClass) {
                return taskletName
                        + type.getName().substring(taskletClass.getName().length());
            }
        }
        return type.getName();
    }

    private static int idOf(String name) {
        int h = name.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return BAND_BASE + (h & BAND_MASK);
    }

    /**
     * The state class, every concrete class nested at any depth in the tasklet class, a class above
     * it, or the state class that implements {@link Change}, and every non-JDK class their fields
     * and record components reach. {@code Void} state contributes nothing of its own.
     */
    @Nonnull
    public static List<Class<?>> discover(@Nonnull Class<?> taskletClass, @Nonnull Class<?> stateClass) {
        Set<Class<?>> found = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        if (stateClass != Void.class) {
            todo.add(stateClass);
            nestedChanges(stateClass, todo);
        }
        for (Class<?> c = taskletClass; c != null && c != Object.class; c = c.getSuperclass()) {
            nestedChanges(c, todo);
        }
        while (!todo.isEmpty()) {
            Class<?> next = todo.poll();
            if (found.add(next)) {
                for (Class<?> field : fieldTypes(next)) {
                    todo.add(field);
                }
            }
        }
        return List.copyOf(found);
    }

    /** One sentence per pair of types deriving the same id; empty when every id is distinct. */
    @Nonnull
    public static List<String> collisions(@Nonnull Map<Class<?>, Integer> ids) {
        Map<Integer, Class<?>> byId = new HashMap<>();
        List<String> out = new ArrayList<>();
        ids.forEach((type, id) -> {
            Class<?> prior = byId.putIfAbsent(id, type);
            if (prior != null) {
                out.add("classes `" + prior.getName() + "` and `" + type.getName() + "` derive the same Kryo id " + id
                        + "; rename one, or list the types in kryoTypes() to own their ids");
            }
        });
        return out;
    }

    private static void nestedChanges(Class<?> owner, Deque<Class<?>> into) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (Change.class.isAssignableFrom(nested) && isConcrete(nested)) {
                into.add(nested);
            }
            nestedChanges(nested, into);
        }
    }

    /** The concrete user classes {@code type}'s instance fields can hold. */
    private static List<Class<?>> fieldTypes(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class && !c.isEnum(); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
                    collect(field.getGenericType(), out);
                }
            }
        }
        return out;
    }

    private static void collect(Type type, List<Class<?>> out) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                collect(c.getComponentType(), out);
            } else {
                concreteUserTypes(c, out);
            }
        } else if (type instanceof ParameterizedType p) {
            collect(p.getRawType(), out);
            for (Type arg : p.getActualTypeArguments()) {
                collect(arg, out);
            }
        } else if (type instanceof GenericArrayType a) {
            collect(a.getGenericComponentType(), out);
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                collect(bound, out);
            }
        }
    }

    /** {@code c} itself when it can be instantiated, else the permitted subclasses a sealed type names. */
    private static void concreteUserTypes(Class<?> c, List<Class<?>> out) {
        if (!isUserType(c)) {
            return;
        }
        if (isConcrete(c)) {
            out.add(c);
        }
        Class<?>[] permitted = c.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> sub : permitted) {
                concreteUserTypes(sub, out);
            }
        }
    }

    private static boolean isUserType(Class<?> c) {
        if (c.isPrimitive() || KryoCodec.isBuiltin(c)) {
            return false;
        }
        String name = c.getName();
        return !(name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun."));
    }

    private static boolean isConcrete(Class<?> c) {
        return !c.isInterface() && !Modifier.isAbstract(c.getModifiers());
    }
}
