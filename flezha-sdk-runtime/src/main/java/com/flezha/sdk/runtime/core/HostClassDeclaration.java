package com.flezha.sdk.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.native_.FlezhaNativeException;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.TaskletModel;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.ChannelEntry;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.ClassFacts;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.PendingEdge;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.TaskletEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
     * Resolve {@code factoryId} in the class registries and encode the record
     * its class was built from, of either kind. Written once at build time, so
     * the bytes the core judged are the bytes it registers.
     */
    @Nonnull
    public static byte[] forFactory(@Nonnull String factoryId) {
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology(factoryId);
        if (topology == null) {
            topology = BoundedOperatorClassRegistry.getTopology(factoryId);
        }
        if (topology == null) {
            throw new IllegalStateException("factory `" + factoryId + "` is not registered");
        }
        return encode(topology.declaration());
    }

    /**
     * The record the builder writes, which an unbounded class submits as is: the
     * class facts as declared, one entry per channel and tasklet in id order,
     * and per channel the chains its handles were given. Consumers, channel
     * kinds, sink names and the acknowledgement granularity are NOT here —
     * they are the core's answer.
     */
    @Nonnull
    public static Map<String, Object> forUnbounded(
            @Nonnull String factoryId,
            @Nonnull ClassFacts facts,
            @Nonnull List<ChannelEntry> channels,
            @Nonnull List<TaskletEntry> tasklets,
            @Nonnull int[] capacities) {
        Map<String, Object> decl = base(factoryId);
        if (facts.persistent()) {
            decl.put("persistent", true);
        }
        if (facts.sleepWhenIdle()) {
            decl.put("sleep_when_idle", true);
        }
        if (facts.effectivelyOnce()) {
            decl.put("effectively_once", true);
        }
        // Every checkpoint knob rides raw; the core derives the granularity
        // from them, which is the rule six per-SDK helpers each carried.
        if (facts.checkpointAck()) {
            decl.put("checkpoint_ack", true);
        }
        if (facts.checkpointEvery() != null) {
            decl.put("checkpoint_every", facts.checkpointEvery());
        }
        if (facts.checkpointMaxDelayMs() != null) {
            decl.put("checkpoint_outputs", "at_checkpoint");
            decl.put("checkpoint_max_delay_ms", facts.checkpointMaxDelayMs());
        }
        if (facts.correctionWindowMs() != null) {
            decl.put("correction_window_ms", facts.correctionWindowMs());
            decl.put("beyond_window_policy", facts.beyondWindowPolicy().wireName());
        }
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
     * declaration, or throws the verdict the core rendered — the names and
     * sentences every SDK reports a refusal under.
     */
    @Nonnull
    public static DerivedDeclaration check(@Nonnull Map<String, Object> record) {
        long ctx = FlzCore.newContext();
        try {
            return DerivedDeclaration.of(JSON.readTree(FlzCore.checkClass(ctx, encode(record))));
        } catch (FlezhaNativeException refusal) {
            throw new UnboundedOperatorConfigurationException(refusal.getMessage(), refusals(refusal.body()));
        } catch (Exception e) {
            throw new IllegalStateException("the core's derived declaration did not decode: " + e.getMessage(), e);
        } finally {
            FlzCore.contextFree(ctx);
        }
    }

    /**
     * The names the core listed in the verdict it handed out beside the
     * refusal. Empty for a refusal that carried no verdict — a call the JNI
     * layer could not even submit, which the core never named.
     */
    @Nonnull
    private static List<String> refusals(@Nullable byte[] verdict) {
        if (verdict == null || verdict.length == 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try {
            for (JsonNode issue : JSON.readTree(verdict).path("issues")) {
                names.add(issue.path("name").asText());
            }
        } catch (IOException e) {
            throw new IllegalStateException("the core's verdict did not decode: " + e.getMessage(), e);
        }
        return names;
    }

    /**
     * The record a bounded class submits: the {@link #forUnbounded} record its
     * {@code channels} told the builder, every tier, checkpoint and correction
     * key included, plus the class's own durability and the {@code bounded}
     * block. The core judges a record carrying that block on a bounded operator's
     * settings and refuses an unbounded-only one by its own name.
     */
    @Nonnull
    public static Map<String, Object> forBounded(
            @Nonnull Map<String, Object> builderRecord, @Nonnull BoundedOperator probe) {
        Map<String, Object> decl = new LinkedHashMap<>(builderRecord);
        if (probe.syncDurability()) {
            decl.put("sync_durability", true);
        }

        Map<String, Object> bounded = new LinkedHashMap<>();
        bounded.put(
                "loop_mode",
                switch (probe.loopMode()) {
                    case ONCE -> "once";
                    case PER_INPUT -> "per_input";
                    case CHAINED -> "chained";
                });
        bounded.put(
                "rollback_mode",
                switch (probe.rollbackMode()) {
                    case ALL_OR_NOTHING -> "all_or_nothing";
                    case KEEP_PROGRESS -> "keep_progress";
                });
        probe.maxDuration().ifPresent(d -> bounded.put("max_duration_ms", d.toMillis()));
        decl.put("bounded", bounded);
        return decl;
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
