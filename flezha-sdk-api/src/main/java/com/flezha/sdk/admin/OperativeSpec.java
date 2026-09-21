package com.flezha.sdk.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What an operative start takes, which is what {@code flz operative start}
 * takes: the factory that serves it, and optionally its id, its kind, the
 * values it is seeded with and the start options.
 *
 * <p>Instances are immutable; each setter answers a new spec, so a spec is
 * safe to keep and reuse.
 */
public final class OperativeSpec {

    private final String id;
    private final String factoryId;
    private final String kind;
    private final JsonNode initValues;
    private final JsonNode opts;

    private OperativeSpec(
            @Nullable String id,
            String factoryId,
            @Nullable String kind,
            @Nullable JsonNode initValues,
            @Nullable JsonNode opts) {
        this.id = id;
        this.factoryId = factoryId;
        this.kind = kind;
        this.initValues = initValues;
        this.opts = opts;
    }

    /**
     * A stream operative served by {@code factoryId}, under an id the
     * controller generates unless {@link #id} names one.
     */
    @Nonnull
    public static OperativeSpec of(String factoryId) {
        return new OperativeSpec(null, factoryId, null, null, null);
    }

    /**
     * The operative's own id. Without one every start runs a new operative
     * under a generated id, so a start retried after a lost answer starts a
     * second one, and a second stream runs until it is stopped. With one, a
     * second start leaves the running operative untouched.
     */
    @Nonnull
    public OperativeSpec id(String id) {
        return new OperativeSpec(id, factoryId, kind, initValues, opts);
    }

    /** The operative kind: {@code "stream"} (the default) or {@code "workflow"}. */
    @Nonnull
    public OperativeSpec kind(String kind) {
        return new OperativeSpec(id, factoryId, kind, initValues, opts);
    }

    /** The values the factory is seeded with, keyed by tasklet. */
    @Nonnull
    public OperativeSpec initValues(JsonNode values) {
        return new OperativeSpec(id, factoryId, kind, values, opts);
    }

    /** The start options, as {@code flz operative start} takes them. */
    @Nonnull
    public OperativeSpec opts(JsonNode opts) {
        return new OperativeSpec(id, factoryId, kind, initValues, opts);
    }

    /** The named parameters the controller builds the operative from; no id asks for a generated one. */
    @Nonnull
    ObjectNode fields() {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("factory_id", factoryId);
        if (id != null) {
            fields.put("id", id);
        }
        if (kind != null) {
            fields.put("kind", kind);
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
