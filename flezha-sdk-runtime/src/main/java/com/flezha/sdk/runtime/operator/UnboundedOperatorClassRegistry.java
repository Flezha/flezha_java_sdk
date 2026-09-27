package com.flezha.sdk.runtime.operator;

import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.internal.ShortFormDeclaration;
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
    public static String registerClass(@Nonnull Class<?> operatorClass) {
        UnboundedOperator operator = probe(operatorClass, UnboundedOperator.class);
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
     * The operator a registered class declares, of either kind: an instance of an
     * operator class, or the one a short-form tasklet class describes. Refuses an
     * operator class with no public no-arg constructor, and any with no factory
     * id. Both class registries register through it.
     *
     * @throws IllegalArgumentException naming {@code kind} and the class.
     */
    @Nonnull
    public static <T extends Operator> T probe(@Nonnull Class<?> operatorClass, @Nonnull Class<T> kind) {
        ShortFormDeclaration shortForm = ShortFormDeclaration.of(operatorClass);
        if (shortForm != null) {
            return kind.cast(ShortForm.of(operatorClass, shortForm));
        }
        String label = kind.getSimpleName();
        T operator;
        try {
            operator = kind.cast(RuntimeBridge.construct(operatorClass));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    label + " class `" + operatorClass.getName() + "` must have a public no-arg constructor: " + e, e);
        }
        String factoryId = operator.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " class `" + operatorClass.getName() + "` returned null/empty from factoryId()");
        }
        return operator;
    }

    /**
     * On-demand removal ({@code FlezhaRunner.unregisterFactory}). Returns the
     * removed class (for {@code FlezhaImpl.removeType}'s re-register-on-live-hit
     * path), or {@code null} when the id is unknown. Reached reflectively from
     * the api module's {@code RegistryCore} on the pre-start path.
     */
    public static Class<?> unregisterClass(@Nonnull String factoryId) {
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

    private record Entry(Class<?> operatorClass, UnboundedOperatorTopology topology) {}
}
