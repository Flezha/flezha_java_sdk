package com.flezha.sdk.control;

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
 * <p>Sending to a relay is not here: it is
 * {@link com.flezha.sdk.FlezhaRunner#send(String, String, String, com.flezha.sdk.SendOpts)}.
 *
 * <p>Every method here is one line over a single native entry point, because
 * the verb travels as data. Adding a verb costs a method and no JNI. A refused call throws
 * {@link FlezhaException} with the code the controller answered, such as {@code quota.exceeded}.
 */
public final class Control {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunnerHandle handle;

    public Control(RunnerHandle handle) {
        this.handle = handle;
    }

    /**
     * Start a relay, answering the id it runs under and what the start
     * did. A refused start throws, as every refused call here does.
     */
    @Nonnull
    public StartResult startRelay(RelaySpec spec) {
        JsonNode answer = call("Relay.start", spec.fields());
        try {
            String outcome = answer.required("outcome").asText().toUpperCase(Locale.ROOT);
            return new StartResult(answer.required("id").asText(), StartOutcome.valueOf(outcome));
        } catch (IllegalArgumentException e) {
            throw FlezhaException.of("control.unreadable_answer", Map.of("verb", "Relay.start"), e);
        }
    }

    /** Stop a relay, or the whole partition group it names. */
    @Nonnull
    public JsonNode stopRelay(String relayId) {
        return call("Relay.stop", relayId);
    }

    /** Enable the plugin {@code name} in this namespace, as {@code spec} describes it. */
    @Nonnull
    public JsonNode enablePlugin(String name, PluginSpec spec) {
        return call("PluginManager.enable", name, spec.fields());
    }

    /** Bind the relay {@code relayId} to the enabled plugin {@code name}, with no hooks. */
    @Nonnull
    public JsonNode bindPlugin(String name, String relayId) {
        return bindPlugin(name, relayId, null, null);
    }

    /**
     * Bind the relay {@code relayId} to the enabled plugin {@code name}.
     *
     * @param hooks names or {@code [name, opts]} pairs, or null for none
     * @param channel the channel the plugin feeds or reads, or null for none
     */
    @Nonnull
    public JsonNode bindPlugin(String name, String relayId, @Nullable JsonNode hooks, @Nullable String channel) {
        ObjectNode fields = relayFields(relayId);
        if (hooks != null) {
            fields.set("hooks", hooks);
        }
        if (channel != null) {
            fields.put("channel", channel);
        }
        return call("PluginManager.bind", name, fields);
    }

    /** Unbind the relay {@code relayId} from the plugin {@code name}, which stays enabled. */
    @Nonnull
    public JsonNode unbindPlugin(String name, String relayId) {
        return call("PluginManager.unbind", name, relayFields(relayId));
    }

    /** Disable the plugin {@code name}, stopping it wherever it runs. */
    @Nonnull
    public JsonNode disablePlugin(String name) {
        return call("PluginManager.disable", name, JsonNodeFactory.instance.objectNode());
    }

    /**
     * The relays in this namespace, one row each: its id, health, runner and node, and a partition
     * group's width and members.
     */
    @Nonnull
    public JsonNode listRelays() {
        return call("Relay.list");
    }

    /**
     * The plugins enabled in this namespace, one row each: name, provider, single, bound relays and
     * where it runs.
     */
    @Nonnull
    public JsonNode listPlugins() {
        return call("PluginManager.enabled");
    }

    /**
     * A relay's live state, and the change sequence it was read at. {@code cell} is
     * {@code <tasklet>[/<cell_key>]}: a tasklet's Cells, or one of them; null reads every
     * Cell of the relay.
     */
    @Nonnull
    public JsonNode cellState(String relayId, @Nullable String cell) {
        return cell == null ? cellState(relayId) : call("Relay.cell_state", relayId, cell);
    }

    /** Every Cell of {@code relayId}: {@link #cellState(String, String)} with no selector. */
    @Nonnull
    public JsonNode cellState(String relayId) {
        return call("Relay.cell_state", relayId);
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
        return read(verb, handle.adminCall(verb, array.toString()));
    }

    /** The controller's JSON answer to {@code verb}, refused when it does not read. */
    @Nonnull
    public static JsonNode read(String verb, String answer) {
        try {
            return JSON.readTree(answer);
        } catch (JsonProcessingException e) {
            throw FlezhaException.of("control.unreadable_answer", Map.of("verb", verb), e);
        }
    }

    private static ObjectNode relayFields(String relayId) {
        return JsonNodeFactory.instance.objectNode().put("relay_id", relayId);
    }
}
