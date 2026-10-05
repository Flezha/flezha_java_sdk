package com.flezha.sdk.internal;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.relay.Relay;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * A {@code FlezhaRunner}'s declared factory set. Instance-scoped: each runner
 * carries its own, so a later runner starts with an empty declared set
 * (generation 2 must re-define its types); the runtime-side class registry
 * reached via {@link RuntimeBridge} stays process-global and overwrites on
 * insert.
 *
 * <p><b>Internal.</b> Not part of the SDK's public API; user code never calls
 * this.
 *
 */
public final class RegistryCore {

    private final Object lock = new Object();
    private final Map<String, Class<?>> entries = new LinkedHashMap<>();
    private final String runtimeRegistryFqn;

    /**
     * @param runtimeRegistryFqn the runtime-side class registry reached via
     *     {@link RuntimeBridge} ({@code registerClass(Class)} /
     *     {@code unregisterClass(String)} / {@code reset()})
     */
    public RegistryCore(String runtimeRegistryFqn) {
        this.runtimeRegistryFqn = runtimeRegistryFqn;
    }

    /**
     * Register a class: validate via the runtime side (which returns the
     * controller-facing factory id), then record it here.
     *
     * @return the controller-facing factory id.
     * @throws IllegalArgumentException on a null class.
     * @throws FlezhaException {@code topology.duplicate_factory_id} on a duplicate factory id.
     */
    public String register(Class<?> cls) {
        if (cls == null) {
            throw new IllegalArgumentException("relay class must not be null");
        }
        synchronized (lock) {
            // Delegate to the runtime side (which validates the authoring and
            // returns the controller-facing factory id) before we record it here.
            String factoryId = registerInRuntime(cls);
            Class<?> held = entries.get(factoryId);
            if (held != null) {
                // The runtime registry overwrites on insert: give the id back to the class this
                // runner already holds under it.
                registerInRuntime(held);
                throw FlezhaException.of("topology.duplicate_factory_id", Map.of("factory_id", factoryId));
            }
            entries.put(factoryId, cls);
            return factoryId;
        }
    }

    private String registerInRuntime(Class<?> cls) {
        return (String) RuntimeBridge.invoke(
                runtimeRegistryFqn,
                "registerClass",
                new Class<?>[] {Class.class},
                "Failed to register relay '" + cls.getName() + "'",
                cls);
    }

    private void unregisterInRuntime(String factoryId) {
        RuntimeBridge.invoke(
                runtimeRegistryFqn,
                "unregisterClass",
                new Class<?>[] {String.class},
                "Failed to remove relay factory '" + factoryId + "'",
                factoryId);
    }

    /**
     * The factory id under which {@code cls} is defined in this core.
     *
     * @throws FlezhaException {@code runtime.class_not_registered} naming the class's factory id
     *     when it is not defined here (never defined, or already removed).
     */
    @Nonnull
    public String definedFactoryIdOf(Class<?> cls) {
        synchronized (lock) {
            for (Map.Entry<String, Class<?>> e : entries.entrySet()) {
                if (e.getValue().equals(cls)) {
                    return e.getKey();
                }
            }
        }
        throw notDefined(cls);
    }

    /**
     * Remove {@code factoryId}: unregister the class from the runtime-side
     * registry and drop the entry here. Pre-start path — no live instance can
     * exist, so no in-use check is needed.
     */
    public void remove(String factoryId) {
        synchronized (lock) {
            unregisterInRuntime(factoryId);
            dropEntry(factoryId);
        }
    }

    /**
     * Drop {@code factoryId}'s entry WITHOUT touching the runtime registry —
     * the post-start path, where the runtime's {@code removeType} already
     * unregistered it under the race-free live-instance check.
     */
    public void dropEntry(String factoryId) {
        synchronized (lock) {
            entries.remove(factoryId);
        }
    }

    /** Thaw and clear the registry (and its runtime side). For tests. */
    public void reset() {
        synchronized (lock) {
            entries.clear();
            RuntimeBridge.invokeIfPresent(runtimeRegistryFqn, "reset");
        }
    }

    @Nonnull
    public List<String> factoryIds() {
        synchronized (lock) {
            return List.copyOf(entries.keySet());
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return entries.isEmpty();
        }
    }

    public boolean contains(String factoryId) {
        synchronized (lock) {
            return entries.containsKey(factoryId);
        }
    }

    /**
     * The refusal for a class not defined here, naming its self-declared
     * factory id (a short form's annotation id), or its class name when its
     * no-arg constructor cannot be called. What that constructor throws is the
     * refusal's cause.
     */
    private FlezhaException notDefined(Class<?> cls) {
        ShortFormDeclaration shortForm = ShortFormDeclaration.of(cls);
        String factoryId = shortForm == null ? null : shortForm.id();
        Throwable cause = null;
        try {
            if (factoryId == null && Relay.class.isAssignableFrom(cls)) {
                factoryId = ((Relay) cls.getDeclaredConstructor().newInstance()).factoryId();
            }
        } catch (InvocationTargetException e) {
            cause = e.getCause();
        } catch (ReflectiveOperationException e) {
            // No callable constructor: the class name stands in for the id.
        }
        return FlezhaException.of(
                "runtime.class_not_registered",
                Map.of("factory", factoryId == null ? cls.getName() : factoryId),
                cause);
    }
}
