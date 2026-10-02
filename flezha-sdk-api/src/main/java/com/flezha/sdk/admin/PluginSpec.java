package com.flezha.sdk.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a plugin enable takes beside the plugin's name, which is what
 * {@code flz plugin enable} takes: the provider, and optionally its options,
 * {@code single}, and an operator to bind in the same call.
 *
 * <p>Instances are immutable; each setter answers a new spec, so a spec is
 * safe to keep and reuse.
 */
public final class PluginSpec {

    private final String provider;
    private final JsonNode opts;
    private final boolean single;
    private final String operatorId;
    private final JsonNode hooks;

    private PluginSpec(
            String provider,
            @Nullable JsonNode opts,
            boolean single,
            @Nullable String operatorId,
            @Nullable JsonNode hooks) {
        this.provider = provider;
        this.opts = opts;
        this.single = single;
        this.operatorId = operatorId;
        this.hooks = hooks;
    }

    /** A plugin of {@code provider}, for example {@code operator_websocket}, on no options. */
    @Nonnull
    public static PluginSpec of(String provider) {
        return new PluginSpec(provider, null, false, null, null);
    }

    /** The provider's options. */
    @Nonnull
    public PluginSpec opts(JsonNode opts) {
        return new PluginSpec(provider, opts, single, operatorId, hooks);
    }

    /** Run one copy in the whole cluster. */
    @Nonnull
    public PluginSpec single() {
        return new PluginSpec(provider, opts, true, operatorId, hooks);
    }

    /**
     * Bind {@code operatorId} in the same call, with {@code hooks}: names or
     * {@code [name, opts]} pairs.
     */
    @Nonnull
    public PluginSpec bind(String operatorId, JsonNode hooks) {
        return new PluginSpec(provider, opts, single, operatorId, hooks);
    }

    /** The named parameters the controller builds the plugin from. */
    @Nonnull
    ObjectNode fields() {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("provider", provider);
        fields.set("opts", opts != null ? opts : JsonNodeFactory.instance.objectNode());
        if (single) {
            fields.put("single", true);
        }
        if (operatorId != null) {
            fields.put("operator_id", operatorId);
        }
        if (hooks != null) {
            fields.set("hooks", hooks);
        }
        return fields;
    }
}
