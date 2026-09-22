package com.flezha.sdk.runtime.operative;

import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.operative.Operative;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.operative.StreamOperativeConfigurationException;
import com.flezha.sdk.runtime.operative.builder.StreamOperativeTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered StreamOperative classes keyed by
 * {@code factoryId}. Holds the immutable {@link StreamOperativeTopology} produced by
 * {@link StreamOperativeTopologyBuilder} at registration time, plus the
 * {@code Class<? extends StreamOperative>} for diagnostic logging.
 *
 * <p>Reached reflectively from the api module's {@code RegistryCore} so that
 * {@code flezha-sdk-api} compiles standalone. Overwrite-on-insert: the
 * per-runner declared set (api side) owns duplicate detection, so a restarted
 * runner generation re-registering a class is idempotent here.
 */
public final class StreamOperativeClassRegistry {

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private StreamOperativeClassRegistry() {}

    /**
     * Reflective entry point invoked from the api module's
     * {@code RegistryCore}. Instantiates the StreamOperative
     * class via its no-arg ctor, builds the topology, and records
     * {@code (factoryId → topology + class)} — overwriting any prior
     * registration of the id. Returns the controller-facing
     * factory id so the caller can de-duplicate on its side.
     *
     * @throws StreamOperativeConfigurationException if topology validation fails.
     * @throws IllegalArgumentException if the StreamOperative class lacks a public
     *     no-arg constructor.
     */
    @Nonnull
    public static String registerClass(@Nonnull Class<? extends StreamOperative> operativeClass) {
        StreamOperative operative = probe(operativeClass, "StreamOperative");
        String factoryId = operative.factoryId();

        if (com.flezha.sdk.runtime.workflow.WorkflowOperativeClassRegistry.getTopology(factoryId) != null) {
            throw new IllegalArgumentException("DuplicateFactoryId: `" + factoryId + "` for StreamOperative class `"
                    + operativeClass.getName() + "` (already registered as a WorkflowOperative)");
        }

        StreamOperativeTopology topology = StreamOperativeTopologyBuilder.build(operative);

        ENTRIES.put(factoryId, new Entry(operativeClass, topology));
        return factoryId;
    }

    /**
     * Instantiate an operative class of either kind to read its declaration,
     * refusing one with no public no-arg constructor or no factory id. Both
     * class registries register through it.
     *
     * @throws IllegalArgumentException naming {@code kind} and the class.
     */
    @Nonnull
    public static <T extends Operative> T probe(@Nonnull Class<T> operativeClass, @Nonnull String kind) {
        T operative;
        try {
            operative = RuntimeBridge.construct(operativeClass);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    kind + " class `" + operativeClass.getName() + "` must have a public no-arg constructor: " + e, e);
        }
        String factoryId = operative.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " class `" + operativeClass.getName() + "` returned null/empty from factoryId()");
        }
        return operative;
    }

    /**
     * On-demand removal ({@code FlezhaRunner.unregisterFactory}). Returns the
     * removed class (for {@code FlezhaImpl.removeType}'s re-register-on-live-hit
     * path), or {@code null} when the id is unknown. Reached reflectively from
     * the api module's {@code RegistryCore} on the pre-start path.
     */
    public static Class<? extends StreamOperative> unregisterClass(@Nonnull String factoryId) {
        Entry removed = ENTRIES.remove(factoryId);
        return removed == null ? null : removed.operativeClass();
    }

    public static StreamOperativeTopology getTopology(@Nonnull String factoryId) {
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

    private record Entry(Class<? extends StreamOperative> operativeClass, StreamOperativeTopology topology) {}
}
