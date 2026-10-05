package com.flezha.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.relay.Relay.Ack;
import com.flezha.sdk.relay.Relay.BeyondWindow;
import com.flezha.sdk.relay.Relay.Durable;
import com.flezha.sdk.relay.Relay.Each;
import com.flezha.sdk.relay.Relay.Exactly;
import com.flezha.sdk.relay.Relay.OnFailure;
import com.flezha.sdk.relay.Relay.Outputs;
import com.flezha.sdk.relay.Relay.Run;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The reference fixture's {@code class.run(<tree>)} and {@code class.durable(<tree>)} steps, read
 * into this SDK's typed tree. Only a tree the types can hold reaches here: a row that writes a
 * setting under the wrong parent needs {@code untyped_tree} and is answered by its compile-fail
 * fixture instead.
 */
final class SettingsTreeJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SettingsTreeJson() {}

    static Run run(String tree) {
        JsonNode node = parse(tree);
        if (branch(node).equals("steps")) {
            JsonNode steps = members(node);
            return new Run.Steps(
                    Each.valueOf(text(steps, "each", "once")), OnFailure.valueOf(text(steps, "on_failure", "undo_all")));
        }
        JsonNode exactly = members(node).path("exactly");
        if (exactly.isMissingNode() || branch(exactly).equals("at_least_once")) {
            return Run.flow();
        }
        JsonNode tier = members(exactly);
        return new Run.Flow(new Exactly.EffectivelyOnce(
                ack(tier.path("ack")),
                tier.has("correction_ms") ? OptionalLong.of(tier.get("correction_ms").asLong()) : OptionalLong.empty(),
                BeyondWindow.valueOf(text(tier, "beyond_window", "process_as_arrival"))));
    }

    static Durable durable(String tree) {
        JsonNode node = parse(tree);
        if (node.isBoolean() && !node.asBoolean()) {
            return new Durable.Memory();
        }
        JsonNode after = members(node).path("sleep_after");
        Durable.SleepAfter sleepAfter = after.isMissingNode()
                ? new Durable.SleepAfter.RunDefault()
                : after.isBoolean()
                        ? new Durable.SleepAfter.Never()
                        : new Durable.SleepAfter.After(Duration.ofMillis(after.asLong()));
        return branch(node).equals("remote") ? new Durable.Remote(sleepAfter) : new Durable.Local(sleepAfter);
    }

    private static Ack ack(JsonNode node) {
        if (node.isMissingNode() || branch(node).equals("per_input")) {
            return new Ack.PerInput();
        }
        JsonNode checkpoint = members(node);
        return new Ack.Checkpoint(
                checkpoint.has("every") ? OptionalInt.of(checkpoint.get("every").asInt()) : OptionalInt.empty(),
                checkpoint.has("max_delay_ms")
                        ? OptionalLong.of(checkpoint.get("max_delay_ms").asLong())
                        : OptionalLong.empty(),
                Outputs.valueOf(text(checkpoint, "outputs", "as_produced")));
    }

    /** A branch is a bare name or a one-key object whose value holds its members. */
    private static String branch(JsonNode node) {
        return node.isTextual() ? node.asText() : node.fieldNames().next();
    }

    private static JsonNode members(JsonNode node) {
        return node.isTextual() ? JSON.createObjectNode() : node.elements().next();
    }

    private static String text(JsonNode members, String field, String fallback) {
        return members.path(field).asText(fallback).toUpperCase(Locale.ROOT);
    }

    private static JsonNode parse(String tree) {
        try {
            return JSON.readTree(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the fixture writes a class setting that is not JSON: " + tree, e);
        }
    }
}
