package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.internal.ShortFormDeclaration;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Process-global registry of registered relay classes keyed by
 * {@code factoryId}. Holds the immutable {@link RelayTopology} produced by
 * {@link RelayTopologyBuilder} at registration time, plus the class for
 * diagnostic logging.
 *
 * <p>Reached reflectively from the api module's {@code RegistryCore} so that
 * {@code flezha-sdk-api} compiles standalone. Overwrite-on-insert: the
 * per-runner declared set (api side) owns duplicate detection, so a restarted
 * runner generation re-registering a class is idempotent here.
 */
public final class RelayClassRegistry {

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RelayClassRegistry() {}

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
    public static String registerClass(@Nonnull Class<?> relayClass) {
        Relay relay = probe(relayClass);
        RelayTopology topology = RelayTopologyBuilder.build(relay);
        ENTRIES.put(relay.factoryId(), new Entry(relayClass, topology));
        return relay.factoryId();
    }

    /**
     * The relay a registered class declares: an instance of a relay
     * class, or the one a short-form tasklet class describes. Refuses an
     * relay class with no public no-arg constructor, and any with no factory
     * id.
     *
     * @throws FlezhaException {@code topology.class_not_constructible} or
     *     {@code topology.missing_factory_id}, naming the class.
     */
    @Nonnull
    public static Relay probe(@Nonnull Class<?> relayClass) {
        ShortFormDeclaration shortForm = ShortFormDeclaration.of(relayClass);
        if (shortForm != null) {
            return ShortForm.of(relayClass, shortForm);
        }
        Relay relay;
        try {
            relay = (Relay) RuntimeBridge.construct(relayClass);
        } catch (ReflectiveOperationException e) {
            throw FlezhaException.of(
                    "topology.class_not_constructible",
                    Map.of("class", relayClass.getName(), "reason", e.toString()),
                    e);
        }
        String factoryId = relay.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw FlezhaException.of("topology.missing_factory_id", Map.of("relay", relayClass.getName()));
        }
        return relay;
    }

    /**
     * On-demand removal ({@code FlezhaRunner.unregisterFactory}). Returns the
     * removed class (for {@code FlezhaImpl.removeType}'s re-register-on-live-hit
     * path), or {@code null} when the id is unknown. Reached reflectively from
     * the api module's {@code RegistryCore} on the pre-start path.
     */
    public static Class<?> unregisterClass(@Nonnull String factoryId) {
        Entry removed = ENTRIES.remove(factoryId);
        return removed == null ? null : removed.relayClass();
    }

    public static RelayTopology getTopology(@Nonnull String factoryId) {
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

    private record Entry(Class<?> relayClass, RelayTopology topology) {}
}
