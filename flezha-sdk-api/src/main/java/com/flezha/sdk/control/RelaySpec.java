package com.flezha.sdk.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a relay start takes, which is what {@code flz relay start}
 * takes: the factory that serves it, and optionally its id, the values it is
 * seeded with and the start options. How it runs is the class's, announced
 * by its runner.
 *
 * <p>Instances are immutable; each setter answers a new spec, so a spec is
 * safe to keep and reuse.
 */
public final class RelaySpec {

    private final String id;
    private final String factoryId;
    private final JsonNode initValues;
    private final JsonNode opts;

    private RelaySpec(@Nullable String id, String factoryId, @Nullable JsonNode initValues, @Nullable JsonNode opts) {
        this.id = id;
        this.factoryId = factoryId;
        this.initValues = initValues;
        this.opts = opts;
    }

    /**
     * A relay of {@code factoryId}, running as its class declares,
     * under an id the controller generates unless {@link #id} names one.
     */
    @Nonnull
    public static RelaySpec of(String factoryId) {
        return new RelaySpec(null, factoryId, null, null);
    }

    /**
     * The relay's own id. Without one every start runs a new relay
     * under a generated id, so a start retried after a lost answer starts a
     * second one, and a second stream runs until it is stopped. With one, a
     * second start leaves the running relay untouched.
     */
    @Nonnull
    public RelaySpec id(String id) {
        return new RelaySpec(id, factoryId, initValues, opts);
    }

    /** The values the factory is seeded with, keyed by tasklet. */
    @Nonnull
    public RelaySpec initValues(JsonNode values) {
        return new RelaySpec(id, factoryId, values, opts);
    }

    /** The start options, as {@code flz relay start} takes them. */
    @Nonnull
    public RelaySpec opts(JsonNode opts) {
        return new RelaySpec(id, factoryId, initValues, opts);
    }

    /** The named parameters the controller builds the relay from; no id asks for a generated one. */
    @Nonnull
    ObjectNode fields() {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("factory_id", factoryId);
        if (id != null) {
            fields.put("id", id);
        }
        if (initValues != null) {
            fields.set("init_values", initValues);
        }
        if (opts != null) {
            fields.set("opts", opts);
        }
        return fields;
    }
}
