package com.flezha.sdk.runtime.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.relay.RelayTopology;
import com.flezha.sdk.runtime.relay.TaskletModel;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder.ChannelEntry;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder.PendingEdge;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder.TaskletEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Writes the poll core's {@code HostClassDecl} record — what the builder was
 * TOLD, never what it concluded — and submits it to the core's one check. The
 * key set is a cross-SDK contract pinned by
 * {@code test/fixtures/host_class_decl_keys.txt}; the core's serde denies
 * unknown fields and defaults absent ones, so optionals that are absent are
 * omitted entirely.
 *
 * <p><b>Id order is the contract:</b> {@code channels} is emitted in Java's
 * channel-id order and {@code tasklets} in tasklet-id order, so the core's
 * indices equal Java's — {@code InputItem.channel} and
 * {@code Output.target.channel} are these indexes.
 */
public final class HostClassDeclaration {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The SDK/controller-aligned default; emitting it would be redundant. */
    private static final long DEFAULT_COMPACT_AFTER_BYTES = 8L * 1024 * 1024;

    /** Header lang byte for durable records (Rust = 1, Java = 2). */
    private static final int SDK_LANG_JAVA = 2;

    /**
     * This SDK's own released version, stamped into every durable record it
     * writes. Pinned to {@code sdk/java/pom.xml} by
     * {@code test/sdk_conformance/gate_test.exs}; a source constant rather than a
     * filtered resource, because the shade plugin replaces the jar plugin's
     * output in place, so a version written into the thin jar's manifest is not
     * what the fat jar carries. The core packs it.
     */
    private static final String SDK_VERSION = "0.1.0";

    /** Header format byte for the host's plain change bodies (Kryo = 4). */
    private static final int FORMAT_ID_KRYO = 4;

    private HostClassDeclaration() {}

    /**
     * Resolve {@code factoryId} in the class registry and encode the record
     * its class was built from. Written once at build time, so the bytes the
     * core judged are the bytes it registers.
     */
    @Nonnull
    public static byte[] forFactory(@Nonnull String factoryId) {
        RelayTopology topology = RelayClassRegistry.getTopology(factoryId);
        if (topology == null) {
            throw new IllegalStateException("factory `" + factoryId + "` is not registered");
        }
        return encode(topology.declaration());
    }

    /**
     * The record a class submits: its settings tree as declared, one entry per
     * channel and tasklet in id order, and per channel the chains its handles
     * were given. Consumers, channel kinds, output names and the acknowledgement
     * granularity are NOT here — they are the core's answer.
     */
    @Nonnull
    public static Map<String, Object> forClass(
            @Nonnull String factoryId,
            @Nonnull Relay settings,
            @Nonnull List<ChannelEntry> channels,
            @Nonnull List<TaskletEntry> tasklets,
            @Nonnull int[] capacities) {
        Map<String, Object> decl = base(factoryId);
        if (!settings.description().isEmpty()) {
            decl.put("description", settings.description());
        }
        settings.maxDuration().ifPresent(d -> decl.put("max_duration_ms", d.toMillis()));
        decl.put("run", runJson(settings.run()));
        decl.put("durable", durableJson(settings.durable()));
        long compactAfterBytes = tasklets.stream()
                .mapToLong(p -> p.model.compactAfterBytes())
                .min()
                .orElse(DEFAULT_COMPACT_AFTER_BYTES);
        if (compactAfterBytes != DEFAULT_COMPACT_AFTER_BYTES) {
            decl.put("compact_after_bytes", compactAfterBytes);
        }
        decl.put("channels", channels(channels));
        decl.put("tasklets", tasklets(tasklets, capacities));
        return decl;
    }

    /**
     * Submit a record to {@code flz_core}'s one check. Answers the derived
     * declaration; a refusal is the core's {@code FlezhaException}, whose
     * {@code details.issues} names every refusal by its code.
     */
    @Nonnull
    public static DerivedDeclaration check(@Nonnull Map<String, Object> record) {
        long ctx = FlzCore.newContext();
        try {
            return DerivedDeclaration.of(JSON.readTree(FlzCore.checkClass(ctx, encode(record))));
        } catch (IOException e) {
            throw new IllegalStateException("the core's derived declaration did not decode: " + e.getMessage(), e);
        } finally {
            FlzCore.contextFree(ctx);
        }
    }

    /** {@code run} as the tree's JSON: {@code {"flow":{"exactly":...}}} or {@code {"steps":{...}}}. */
    private static Map<String, Object> runJson(Relay.Run run) {
        return switch (run) {
            case Relay.Run.Flow flow -> Map.of("flow", Map.of("exactly", exactlyJson(flow.exactly())));
            case Relay.Run.Steps steps -> Map.of(
                    "steps",
                    Map.of(
                            "each", steps.each().name().toLowerCase(Locale.ROOT),
                            "on_failure", steps.onFailure().name().toLowerCase(Locale.ROOT)));
        };
    }

    private static Object exactlyJson(Relay.Exactly exactly) {
        if (!(exactly instanceof Relay.Exactly.EffectivelyOnce tier)) {
            return "at_least_once";
        }
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("ack", ackJson(tier.ack()));
        tier.correctionMs().ifPresent(ms -> members.put("correction_ms", ms));
        members.put("beyond_window", tier.beyondWindow().name().toLowerCase(Locale.ROOT));
        return Map.of("effectively_once", members);
    }

    private static Object ackJson(Relay.Ack ack) {
        if (!(ack instanceof Relay.Ack.Checkpoint checkpoint)) {
            return "per_input";
        }
        Map<String, Object> members = new LinkedHashMap<>();
        checkpoint.every().ifPresent(n -> members.put("every", n));
        checkpoint.maxDelayMs().ifPresent(ms -> members.put("max_delay_ms", ms));
        members.put("outputs", checkpoint.outputs().name().toLowerCase(Locale.ROOT));
        return Map.of("checkpoint", members);
    }

    /**
     * {@code durable} as the tree's JSON: {@code false}, or the level with its {@code sleep_after};
     * the run's default leaves {@code sleep_after} out.
     */
    private static Object durableJson(Relay.Durable durable) {
        return switch (durable) {
            case Relay.Durable.Memory ignored -> false;
            case Relay.Durable.Local local -> level("local", local.sleepAfter());
            case Relay.Durable.Remote remote -> level("remote", remote.sleepAfter());
        };
    }

    private static Object level(String name, Relay.Durable.SleepAfter sleepAfter) {
        return switch (sleepAfter) {
            case Relay.Durable.SleepAfter.RunDefault ignored -> name;
            case Relay.Durable.SleepAfter.Never ignored -> Map.of(name, Map.of("sleep_after", false));
            case Relay.Durable.SleepAfter.After after -> Map.of(
                    name, Map.of("sleep_after", after.interval().toMillis()));
        };
    }

    private static Map<String, Object> base(String factoryId) {
        Map<String, Object> decl = new LinkedHashMap<>();
        decl.put("factory_id", factoryId);
        decl.put("sdk_lang", SDK_LANG_JAVA);
        decl.put("sdk_version", SDK_VERSION);
        decl.put("change_format_id", FORMAT_ID_KRYO);
        return decl;
    }

    private static List<Map<String, Object>> channels(List<ChannelEntry> channels) {
        List<Map<String, Object>> out = new ArrayList<>(channels.size());
        for (ChannelEntry channel : channels) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", channel.name);
            c.put("kind", kindOf(channel.kind));
            if (channel.producer != null) {
                c.put("producer", channel.producer);
            }
            c.put("chains", chains(channel));
            c.put("retry_max", channel.retryMaxAttempts);
            c.put("retry_backoff_ms", channel.retryBackoffBaseMs);
            c.put(
                    "overflow",
                    switch (channel.overflowPolicy) {
                        case DROP_NOTHING -> "drop_nothing";
                        case DROP_NEWEST -> "drop_newest";
                        case DROP_OLDEST -> "drop_oldest";
                    });
            if (channel.keyed) {
                c.put("keyed", true);
            }
            out.add(c);
        }
        return out;
    }

    private static List<List<Map<String, Object>>> chains(ChannelEntry channel) {
        List<List<Map<String, Object>>> out = new ArrayList<>(channel.chains.size());
        for (PendingEdge chain : channel.chains) {
            List<Map<String, Object>> links = new ArrayList<>(chain.links.size());
            for (HostLink link : chain.links) {
                links.add(link.json());
            }
            out.add(links);
        }
        return out;
    }

    private static List<Map<String, Object>> tasklets(List<TaskletEntry> tasklets, int[] capacities) {
        List<Map<String, Object>> out = new ArrayList<>(tasklets.size());
        for (int i = 0; i < tasklets.size(); i++) {
            TaskletEntry desc = tasklets.get(i);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", desc.name);
            TaskletModel model = desc.model;
            if (desc.dynamic) {
                p.put("cell_mode", "dynamic");
                model.cellIdleTtl().ifPresent(ttl -> p.put("idle_ttl_ms", ttl.toMillis()));
                model.maxCells().ifPresent(cap -> p.put("max_cells", cap));
            } else if (!model.cellKeys().isEmpty()) {
                p.put("cell_mode", "static");
                p.put("cell_keys", model.cellKeys());
            }
            if (model.cellsForDeclared()) {
                p.put("cells_for_overridden", true);
            }
            if (model.compensateDeclared()) {
                p.put("compensate_overridden", true);
            }
            p.put("clock", model.clock().wireName());
            p.put("mailbox_capacity", capacities[i]);
            // A Void state journals nothing, so a logical clock over it never
            // advances; the core names that.
            if (model.stateless()) {
                p.put("change_is_nochange", true);
            }
            // Per Tasklet, never a class-wide maximum: one Tasklet's bump
            // would otherwise re-stamp its siblings' records with a version
            // they never declared.
            p.put("schema_version", model.schemaVersion());
            p.put("min_readable_schema_version", model.minReadableSchemaVersion());
            out.add(p);
        }
        return out;
    }

    private static String kindOf(ChannelKind kind) {
        return switch (kind) {
            case ENTRY -> "entry";
            case INTER_TASKLET -> "inter_task";
            case PLUGIN_BOUND -> "plugin";
        };
    }

    private static byte[] encode(Map<String, Object> decl) {
        try {
            return JSON.writeValueAsBytes(decl);
        } catch (Exception e) {
            throw new IllegalStateException("class declaration encoding failed: " + e.getMessage(), e);
        }
    }
}
