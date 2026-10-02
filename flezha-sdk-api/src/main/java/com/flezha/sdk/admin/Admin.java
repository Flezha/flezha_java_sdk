package com.flezha.sdk.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.internal.RunnerHandle;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * the verb travels as data. Adding a verb costs a method and no JNI. A refused call throws
 * {@link FlezhaException} with the code the controller answered, such as {@code quota.exceeded}.
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
            throw FlezhaException.of("admin.unreadable_answer", Map.of("verb", "Operator.start"), e);
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

    /** Enable the plugin {@code name} in this namespace, as {@code spec} describes it. */
    @Nonnull
    public JsonNode enablePlugin(String name, PluginSpec spec) {
        return call("PluginManager.enable", name, spec.fields());
    }

    /** Bind the operator {@code operatorId} to the enabled plugin {@code name}, with no hooks. */
    @Nonnull
    public JsonNode bindPlugin(String name, String operatorId) {
        return bindPlugin(name, operatorId, null, null);
    }

    /**
     * Bind the operator {@code operatorId} to the enabled plugin {@code name}.
     *
     * @param hooks names or {@code [name, opts]} pairs, or null for none
     * @param channel the channel the plugin feeds or reads, or null for none
     */
    @Nonnull
    public JsonNode bindPlugin(String name, String operatorId, @Nullable JsonNode hooks, @Nullable String channel) {
        ObjectNode fields = operatorFields(operatorId);
        if (hooks != null) {
            fields.set("hooks", hooks);
        }
        if (channel != null) {
            fields.put("channel", channel);
        }
        return call("PluginManager.bind", name, fields);
    }

    /** Unbind the operator {@code operatorId} from the plugin {@code name}, which stays enabled. */
    @Nonnull
    public JsonNode unbindPlugin(String name, String operatorId) {
        return call("PluginManager.unbind", name, operatorFields(operatorId));
    }

    /** Disable the plugin {@code name}, stopping it wherever it runs. */
    @Nonnull
    public JsonNode disablePlugin(String name) {
        return call("PluginManager.disable", name, JsonNodeFactory.instance.objectNode());
    }

    /** The operator ids running in this namespace. */
    @Nonnull
    public JsonNode listOperators() {
        return call("Operator.list_operators");
    }

    /** The plugins enabled in this namespace: name, provider, single, bound operators. */
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
        String answer = handle.adminCall(verb, array.toString());
        try {
            return JSON.readTree(answer);
        } catch (JsonProcessingException e) {
            throw FlezhaException.of("admin.unreadable_answer", Map.of("verb", verb), e);
        }
    }

    private static ObjectNode operatorFields(String operatorId) {
        return JsonNodeFactory.instance.objectNode().put("operator_id", operatorId);
    }
}
