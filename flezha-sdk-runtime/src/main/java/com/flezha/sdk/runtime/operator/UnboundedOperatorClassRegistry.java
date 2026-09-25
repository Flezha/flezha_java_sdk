package com.flezha.sdk.runtime.operator;

import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered UnboundedOperator classes keyed by
 * {@code factoryId}. Holds the immutable {@link UnboundedOperatorTopology} produced by
 * {@link UnboundedOperatorTopologyBuilder} at registration time, plus the
 * {@code Class<? extends UnboundedOperator>} for diagnostic logging.
 *
 * <p>Reached reflectively from the api module's {@code RegistryCore} so that
 * {@code flezha-sdk-api} compiles standalone. Overwrite-on-insert: the
 * per-runner declared set (api side) owns duplicate detection, so a restarted
 * runner generation re-registering a class is idempotent here.
 */
public final class UnboundedOperatorClassRegistry {

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private UnboundedOperatorClassRegistry() {}

    /**
     * Reflective entry point invoked from the api module's
     * {@code RegistryCore}. Instantiates the UnboundedOperator
     * class via its no-arg ctor, builds the topology, and records
     * {@code (factoryId → topology + class)} — overwriting any prior
     * registration of the id. Returns the controller-facing
     * factory id so the caller can de-duplicate on its side.
     *
     * @throws UnboundedOperatorConfigurationException if topology validation fails.
     * @throws IllegalArgumentException if the UnboundedOperator class lacks a public
     *     no-arg constructor.
     */
    @Nonnull
    public static String registerClass(@Nonnull Class<? extends UnboundedOperator> operatorClass) {
        UnboundedOperator operator = probe(operatorClass, "UnboundedOperator");
        String factoryId = operator.factoryId();

        if (com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.getTopology(factoryId) != null) {
            throw new IllegalArgumentException("DuplicateFactoryId: `" + factoryId + "` for UnboundedOperator class `"
                    + operatorClass.getName() + "` (already registered as a BoundedOperator)");
        }

        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(operator);

        ENTRIES.put(factoryId, new Entry(operatorClass, topology));
        return factoryId;
    }

    /**
     * Instantiate an operator class of either kind to read its declaration,
     * refusing one with no public no-arg constructor or no factory id. Both
     * class registries register through it.
     *
     * @throws IllegalArgumentException naming {@code kind} and the class.
     */
    @Nonnull
    public static <T extends Operator> T probe(@Nonnull Class<T> operatorClass, @Nonnull String kind) {
        T operator;
        try {
            operator = RuntimeBridge.construct(operatorClass);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    kind + " class `" + operatorClass.getName() + "` must have a public no-arg constructor: " + e, e);
        }
        String factoryId = operator.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " class `" + operatorClass.getName() + "` returned null/empty from factoryId()");
        }
        return operator;
    }

    /**
     * On-demand removal ({@code FlezhaRunner.unregisterFactory}). Returns the
     * removed class (for {@code FlezhaImpl.removeType}'s re-register-on-live-hit
     * path), or {@code null} when the id is unknown. Reached reflectively from
     * the api module's {@code RegistryCore} on the pre-start path.
     */
    public static Class<? extends UnboundedOperator> unregisterClass(@Nonnull String factoryId) {
        Entry removed = ENTRIES.remove(factoryId);
        return removed == null ? null : removed.operatorClass();
    }

    public static UnboundedOperatorTopology getTopology(@Nonnull String factoryId) {
        Entry e = ENTRIES.get(factoryId);
        return e == null ? null : e.topology();
    }

    @Nonnull
    public static List<String> factoryIds() {
        return List.copyOf(ENTRIES.keySet());
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /** Test-only — invoked by the api module's {@code RegistryCore.reset()}. */
    public static void reset() {
        ENTRIES.clear();
    }

    private record Entry(Class<? extends UnboundedOperator> operatorClass, UnboundedOperatorTopology topology) {}
}
