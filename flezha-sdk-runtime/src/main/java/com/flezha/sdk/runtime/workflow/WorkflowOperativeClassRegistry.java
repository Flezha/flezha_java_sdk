package com.flezha.sdk.runtime.workflow;

import com.flezha.sdk.operative.StreamOperativeConfigurationException;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.operative.WorkflowOperativeConfigurationException;
import com.flezha.sdk.runtime.operative.StreamOperativeClassRegistry;
import com.flezha.sdk.runtime.operative.StreamOperativeTopology;
import com.flezha.sdk.runtime.operative.builder.StreamOperativeTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered {@link WorkflowOperative} classes keyed by
 * {@code factoryId}, each carrying the frozen {@link StreamOperativeTopology} its
 * {@code channels} built, judged by the core as a workflow's. The runtime sibling of
 * {@link StreamOperativeClassRegistry}; reached reflectively from the api
 * module's {@code RegistryCore} so {@code flezha-sdk-api}
 * compiles standalone. Overwrite-on-insert — the per-runner declared set owns
 * duplicate detection. Looked up when an {@code AddOperative} whose kind
 * resolves to {@code :workflow} arrives.
 */
public final class WorkflowOperativeClassRegistry {

    private static final Map<String, StreamOperativeTopology> ENTRIES = new ConcurrentHashMap<>();

    private WorkflowOperativeClassRegistry() {}

    /**
     * Reflective entry point invoked from the api module's {@code RegistryCore}.
     * Instantiates the class, refuses one that cannot terminate, builds its
     * topology through the builder a stream class uses, then records
     * {@code (factoryId → topology)} — overwriting any prior registration of the
     * id. Every rule of the graph, a stream-only setting included, is the core's
     * and is named by it; so is the one a durable CHAINED loop still owes (a
     * terminal tasklet releasing an inter-tasklet channel), refused in
     * {@code flz_core}'s {@code register_structured_workflow}, which every host
     * reaches through {@code declareClass}. Rejects a factory id that collides
     * with a registered stream operative. Returns the controller-facing factory id.
     *
     * @throws IllegalArgumentException if the WorkflowOperative class lacks a public
     *     no-arg constructor, returns a null/empty factory id, or collides with
     *     an existing operative/workflow.
     * @throws WorkflowOperativeConfigurationException if the authoring is invalid
     *     (external-stop-only, or a graph the core refuses).
     */
    @Nonnull
    public static String registerClass(@Nonnull Class<? extends WorkflowOperative> workflowClass) {
        WorkflowOperative probe = resolveProbe(workflowClass);
        String factoryId = probe.factoryId();

        StreamOperativeTopology topology;
        try {
            topology = StreamOperativeTopologyBuilder.build(probe);
        } catch (StreamOperativeConfigurationException e) {
            throw new WorkflowOperativeConfigurationException(
                    "workflow operative `" + factoryId + "` has an invalid topology: " + e.getMessage(), e);
        }

        if (StreamOperativeClassRegistry.getTopology(factoryId) != null) {
            throw new IllegalArgumentException("DuplicateFactoryId: `" + factoryId + "` for WorkflowOperative class `"
                    + workflowClass.getName() + "` (already registered as a StreamOperative)");
        }

        ENTRIES.put(factoryId, topology);
        return factoryId;
    }

    /**
     * Instantiate the class and refuse an operative that cannot terminate: one
     * whose only possible exit is an external stop, with no duration backstop.
     */
    @Nonnull
    private static WorkflowOperative resolveProbe(Class<? extends WorkflowOperative> operativeClass) {
        WorkflowOperative probe = StreamOperativeClassRegistry.probe(operativeClass, "WorkflowOperative");
        if (!probe.selfTerminating() && probe.maxDuration().isEmpty()) {
            throw new WorkflowOperativeConfigurationException("workflow operative `" + probe.factoryId()
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
    public static StreamOperativeTopology unregisterClass(@Nonnull String factoryId) {
        return ENTRIES.remove(factoryId);
    }

    /** The frozen topology of a registered workflow factory, or {@code null} if unknown. */
    public static StreamOperativeTopology getTopology(@Nonnull String factoryId) {
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
