package com.flezha.sdk.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.flezha.sdk.internal.RunnerHandle;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Administering the namespace this runner's connection proved, from ordinary
 * application code.
 *
 * <p>No tasklet has to be running and the application holds no credential of
 * its own: the controller judges every call as the identity the connection
 * already established, so nothing an unattended process holds expires. An
 * user grants a namespace's applications the authority once and revokes it
 * the same way, and a revocation takes effect on the next call:
 *
 * <pre>{@code flz role bind runner@team-orders app team-orders}</pre>
 *
 * <p>Every method here is one line over a single native entry point, because
 * the verb travels as data. Adding a verb costs a method and no JNI.
 */
public final class Admin {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunnerHandle handle;

    public Admin(RunnerHandle handle) {
        this.handle = handle;
    }

    /**
     * Start an operator, answering the id it runs under and what the start
     * did. A refused start throws, as every refused call here does.
     */
    @Nonnull
    public StartResult startOperator(OperatorSpec spec) {
        JsonNode answer = call("Operator.start", spec.fields());
        try {
            String outcome = answer.required("outcome").asText().toUpperCase(Locale.ROOT);
            return new StartResult(answer.required("id").asText(), StartOutcome.valueOf(outcome));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the controller answered a start this SDK cannot read: " + answer, e);
        }
    }

    /** Stop an operator, or the whole partition group it names. */
    @Nonnull
    public JsonNode stopOperator(String operatorId) {
        return call("Operator.stop", operatorId);
    }

    /**
     * Inject a message into an operator's input stream and block for the
     * delivery verdict. {@code channel} may be empty for none.
     */
    @Nonnull
    public JsonNode send(String operatorId, String message, String channel) {
        return call("Operator.send", operatorId, message, channel);
    }

    /** Enable a plugin, built from the fields {@code flz plugin enable} takes. */
    @Nonnull
    public JsonNode enablePlugin(String pluginId, JsonNode fields) {
        return call("PluginManager.enable", pluginId, fields);
    }

    /** Disable a plugin, or every partitioned instance of it. */
    @Nonnull
    public JsonNode disablePlugin(String pluginId) {
        return call("PluginManager.disable", pluginId);
    }

    /** The operator ids running in this namespace. */
    @Nonnull
    public JsonNode listOperators() {
        return call("Operator.list_operators");
    }

    /** The plugins enabled on the controller node answering this call. */
    @Nonnull
    public JsonNode listPlugins() {
        return call("PluginManager.list");
    }

    /** One cell's live state, and the change sequence it was read at. */
    @Nonnull
    public JsonNode cellState(String operatorId, String cellKey) {
        return call("Operator.cell_state", operatorId, cellKey);
    }

    /**
     * Any published function, by name, for a verb this surface has not yet
     * grown a method for. What succeeds follows from the role the namespace is
     * bound to, never from a list kept here.
     *
     * @param args the call's arguments other than the namespace, which the
     *     controller fills from the connection. A {@link JsonNode} is sent as
     *     it is; anything else is sent as its text.
     */
    @Nonnull
    public JsonNode call(String verb, Object... args) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (Object arg : args) {
            array.add(arg instanceof JsonNode node ? node : JsonNodeFactory.instance.textNode(String.valueOf(arg)));
        }
        return read(handle.adminCall(verb, array.toString()));
    }

    private JsonNode read(String answer) {
        try {
            return JSON.readTree(answer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the controller answered unreadable JSON: " + e.getMessage(), e);
        }
    }
}
