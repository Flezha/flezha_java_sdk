package com.flezha.sdk.runtime.operator;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.internal.ShortFormDeclaration;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.operator.builder.OperatorTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered operator classes keyed by
 * {@code factoryId}. Holds the immutable {@link OperatorTopology} produced by
 * {@link OperatorTopologyBuilder} at registration time, plus the class for
 * diagnostic logging.
 *
 * <p>Reached reflectively from the api module's {@code RegistryCore} so that
 * {@code flezha-sdk-api} compiles standalone. Overwrite-on-insert: the
 * per-runner declared set (api side) owns duplicate detection, so a restarted
 * runner generation re-registering a class is idempotent here.
 */
public final class OperatorClassRegistry {

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private OperatorClassRegistry() {}

    /**
     * Reflective entry point invoked from the api module's {@code RegistryCore}.
     * Instantiates the class via its no-arg ctor, builds the topology, and
     * records {@code (factoryId → topology + class)}, overwriting any prior
     * registration of the id. Returns the controller-facing factory id so the
     * caller can de-duplicate on its side.
     *
     * @throws FlezhaException if the core refuses the class, or the class lacks
     *     a public no-arg constructor.
     */
    @Nonnull
    public static String registerClass(@Nonnull Class<?> operatorClass) {
        Operator operator = probe(operatorClass);
        OperatorTopology topology = OperatorTopologyBuilder.build(operator);
        ENTRIES.put(operator.factoryId(), new Entry(operatorClass, topology));
        return operator.factoryId();
    }

    /**
     * The operator a registered class declares: an instance of an operator
     * class, or the one a short-form tasklet class describes. Refuses an
     * operator class with no public no-arg constructor, and any with no factory
     * id.
     *
     * @throws FlezhaException {@code topology.class_not_constructible} or
     *     {@code topology.missing_factory_id}, naming the class.
     */
    @Nonnull
    public static Operator probe(@Nonnull Class<?> operatorClass) {
        ShortFormDeclaration shortForm = ShortFormDeclaration.of(operatorClass);
        if (shortForm != null) {
            return ShortForm.of(operatorClass, shortForm);
        }
        Operator operator;
        try {
            operator = (Operator) RuntimeBridge.construct(operatorClass);
        } catch (ReflectiveOperationException e) {
            throw FlezhaException.of(
                    "topology.class_not_constructible",
                    Map.of("class", operatorClass.getName(), "reason", e.toString()),
                    e);
        }
        String factoryId = operator.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw FlezhaException.of("topology.missing_factory_id", Map.of("operator", operatorClass.getName()));
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

    public static OperatorTopology getTopology(@Nonnull String factoryId) {
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

    private record Entry(Class<?> operatorClass, OperatorTopology topology) {}
}
