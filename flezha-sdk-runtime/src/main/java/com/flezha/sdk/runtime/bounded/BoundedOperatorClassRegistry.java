package com.flezha.sdk.runtime.bounded;

import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.BoundedOperatorConfigurationException;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered {@link BoundedOperator} classes keyed by
 * {@code factoryId}, each carrying the frozen {@link UnboundedOperatorTopology} its
 * {@code channels} built, judged by the core as a bounded operator's. The runtime sibling of
 * {@link UnboundedOperatorClassRegistry}; reached reflectively from the api
 * module's {@code RegistryCore} so {@code flezha-sdk-api}
 * compiles standalone. Overwrite-on-insert — the per-runner declared set owns
 * duplicate detection. Looked up when an {@code AddOperator} whose kind
 * resolves to {@code :bounded} arrives.
 */
public final class BoundedOperatorClassRegistry {

    private static final Map<String, UnboundedOperatorTopology> ENTRIES = new ConcurrentHashMap<>();

    private BoundedOperatorClassRegistry() {}

    /**
     * Reflective entry point invoked from the api module's {@code RegistryCore}.
     * Instantiates the class, refuses one that cannot terminate, builds its
     * topology through the builder an unbounded class uses, then records
     * {@code (factoryId → topology)} — overwriting any prior registration of the
     * id. Every rule of the graph, an unbounded-only setting included, is the core's
     * and is named by it; so is the one a durable CHAINED loop still owes (a
     * terminal tasklet releasing an inter-tasklet channel), refused in
     * {@code flz_core}'s {@code register_structured_bounded}, which every host
     * reaches through {@code declareClass}. Rejects a factory id that collides
     * with a registered unbounded operator. Returns the controller-facing factory id.
     *
     * @throws IllegalArgumentException if the BoundedOperator class lacks a public
     *     no-arg constructor, returns a null/empty factory id, or collides with
     *     an existing operator/bounded.
     * @throws BoundedOperatorConfigurationException if the authoring is invalid
     *     (external-stop-only, or a graph the core refuses).
     */
    @Nonnull
    public static String registerClass(@Nonnull Class<?> boundedClass) {
        BoundedOperator probe = resolveProbe(boundedClass);
        String factoryId = probe.factoryId();

        UnboundedOperatorTopology topology;
        try {
            topology = UnboundedOperatorTopologyBuilder.build(probe);
        } catch (UnboundedOperatorConfigurationException e) {
            throw new BoundedOperatorConfigurationException(
                    "bounded operator `" + factoryId + "` has an invalid topology: " + e.getMessage(), e);
        }

        if (UnboundedOperatorClassRegistry.getTopology(factoryId) != null) {
            throw new IllegalArgumentException("DuplicateFactoryId: `" + factoryId + "` for BoundedOperator class `"
                    + boundedClass.getName() + "` (already registered as an UnboundedOperator)");
        }

        ENTRIES.put(factoryId, topology);
        return factoryId;
    }

    /**
     * Instantiate the class and refuse an operator that cannot terminate: one
     * whose only possible exit is an external stop, with no duration backstop.
     */
    @Nonnull
    private static BoundedOperator resolveProbe(Class<?> operatorClass) {
        BoundedOperator probe = UnboundedOperatorClassRegistry.probe(operatorClass, BoundedOperator.class);
        if (!probe.selfTerminating() && probe.maxDuration().isEmpty()) {
            throw new BoundedOperatorConfigurationException("bounded operator `" + probe.factoryId()
                    + "` is not terminable: its only possible exit is an external stop. Add a terminal condition: "
                    + "let the chain complete, have a tasklet call ctx.complete, or declare maxDuration().");
        }
        return probe;
    }

    /**
     * On-demand removal ({@code FlezhaRunner.unregisterFactory}). Returns the
     * removed topology, or {@code null} when the id is unknown. Reached
     * reflectively from the api module's {@code RegistryCore} on the pre-start
     * path.
     */
    public static UnboundedOperatorTopology unregisterClass(@Nonnull String factoryId) {
        return ENTRIES.remove(factoryId);
    }

    /** The frozen topology of a registered bounded factory, or {@code null} if unknown. */
    public static UnboundedOperatorTopology getTopology(@Nonnull String factoryId) {
        return ENTRIES.get(factoryId);
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
}
