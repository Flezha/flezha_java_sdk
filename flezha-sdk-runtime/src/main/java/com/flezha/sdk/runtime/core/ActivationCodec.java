package com.flezha.sdk.runtime.core;

import com.flezha.sdk.runtime.state.Postcard;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.state.TimerFiring;
import com.flezha.sdk.state.TimerKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * Postcard codec for the poll-core activation boundary (schema version 4),
 * byte-identical to {@code flz_core::schema}: the shim decodes Activations and
 * encodes ActivationResults on the hot path. The inverses the cross-language
 * fixtures need live in the test sources.
 */
public final class ActivationCodec {

    private ActivationCodec() {}

    // ------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------

    @Nonnull
    public static Activation decodeActivation(@Nonnull byte[] bytes) {
        Postcard.Reader r = new Postcard.Reader(bytes);
        Activation a =
                new Activation(r.varint(), decodeCellRef(r), decodeKind(r), r.varint(), r.string(), r.optionalBytes());
        if (!r.exhausted()) {
            throw new IllegalArgumentException("postcard: trailing bytes after Activation at " + r.pos());
        }
        return a;
    }

    // ------------------------------------------------------------------
    // ActivationResult
    // ------------------------------------------------------------------

    @Nonnull
    public static byte[] encodeResult(@Nonnull ActivationResult result) {
        Postcard.Writer w = new Postcard.Writer();
        w.varint(result.token());
        encodeOutcome(w, result.outcome());
        return w.toBytes();
    }

    // ------------------------------------------------------------------
    // Output on its own
    // ------------------------------------------------------------------

    /** One {@link Output} released on an output in local mode. */
    @Nonnull
    public static Output decodeOutput(@Nonnull byte[] bytes) {
        Postcard.Reader r = new Postcard.Reader(bytes);
        Output output = decodeOutput(r);
        if (!r.exhausted()) {
            throw new IllegalArgumentException("postcard: trailing bytes after Output at " + r.pos());
        }
        return output;
    }

    // ------------------------------------------------------------------
    // Activation internals
    // ------------------------------------------------------------------

    private static ActivationKind.RestoreBody decodeRestoreBody(Postcard.Reader r) {
        byte[] bytes = r.bytes();
        OptionalLong from = r.optionalVarint();
        return new ActivationKind.RestoreBody(
                bytes, from.isPresent() ? OptionalInt.of(Math.toIntExact(from.getAsLong())) : OptionalInt.empty());
    }

    private static CellRef decodeCellRef(Postcard.Reader r) {
        return new CellRef(r.string(), Math.toIntExact(r.varint()), optionalString(r));
    }

    private static ActivationKind decodeKind(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        return switch (discriminant) {
            case 0 -> new ActivationKind.Work(r.vec(ActivationCodec::decodeWorkItem));
            case 1 -> new ActivationKind.RestoreFold(
                    r.some() ? Optional.of(decodeRestoreBody(r)) : Optional.empty(),
                    r.vec(ActivationCodec::decodeRestoreBody));
            case 2 -> new ActivationKind.SnapshotRequest();
            case 3 -> new ActivationKind.LiveStateRead();
            case 4 -> new ActivationKind.ApplyState(r.bytes());
            case 5 -> new ActivationKind.KeyRequest(
                    Math.toIntExact(r.varint()), r.vec(Postcard.Reader::bytes), r.bool(), r.bool());
            case 6 -> new ActivationKind.CompensateRun(r.vec(Postcard.Reader::bytes));
            case 7 -> new ActivationKind.Leave(r.bool());
            default -> throw new IllegalArgumentException(
                    "postcard: unknown ActivationKind discriminant " + discriminant);
        };
    }

    private static WorkItem decodeWorkItem(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        return switch (discriminant) {
            case 0 -> decodeInput(r);
            case 1 -> new WorkItem.Timer(new TimerFiring(timerKind(r), r.varint()));
            default -> throw new IllegalArgumentException("postcard: unknown WorkItem discriminant " + discriminant);
        };
    }

    private static WorkItem.Input decodeInput(Postcard.Reader r) {
        int channel = Math.toIntExact(r.varint());
        byte[] payload = r.bytes();
        List<Map.Entry<String, String>> metadata = r.vec(m -> Map.entry(m.string(), m.string()));
        OptionalLong consumedOffset = r.optionalVarint();
        boolean replay = r.bool();
        long now = r.varint();
        WorkItem.Form form = variant(r, WorkItem.Form.values());
        List<byte[]> values = form == WorkItem.Form.PREPARED ? r.vec(Postcard.Reader::bytes) : List.of();
        return new WorkItem.Input(channel, payload, metadata, consumedOffset, replay, now, form, values);
    }

    // ------------------------------------------------------------------
    // ActivationResult internals
    // ------------------------------------------------------------------

    private static void encodeOutcome(Postcard.Writer w, ActivationOutcome outcome) {
        switch (outcome) {
            case ActivationOutcome.Work work -> w.varint(0).vec(work.effects(), ActivationCodec::encodeItemEffect);
            case ActivationOutcome.Restored ignored -> w.varint(1);
            case ActivationOutcome.Snapshot snapshot -> w.varint(2).bytes(snapshot.state());
            case ActivationOutcome.StateJson state -> w.varint(3).bytes(state.json());
            case ActivationOutcome.StateApplied ignored -> w.varint(4);
            case ActivationOutcome.Prepared prepared -> w.varint(5)
                    .vec(prepared.answers(), ActivationCodec::encodePrepareResult);
            case ActivationOutcome.Failed failed -> {
                w.varint(6);
                encodeWorkError(w, failed.error());
            }
        }
    }

    private static void encodeItemEffect(Postcard.Writer w, ItemEffect effect) {
        encodeVerdict(w, effect.verdict());
        w.vec(effect.changes(), Postcard.Writer::bytes);
        w.vec(effect.outputs(), ActivationCodec::encodeOutput);
        w.optionalVarint(effect.consumedOffset()).varint(effect.eventTime());
        w.vec(effect.timers(), TimerOp::encode);
        w.bool(effect.checkpoint());
        w.optional(effect.dlq(), DlqEntry::encode);
        w.optionalBytes(effect.complete());
    }

    private static void encodeVerdict(Postcard.Writer w, Verdict verdict) {
        switch (verdict) {
            case Verdict.Ok ignored -> w.varint(0);
            case Verdict.RetryableError error -> w.varint(1).string(error.message());
            case Verdict.NonRetryableError error -> w.varint(2).string(error.message());
            case Verdict.Unreadable error -> w.varint(3).string(error.message());
        }
    }

    private static void encodeOutput(Postcard.Writer w, Output output) {
        switch (output.target()) {
            case OutputTarget.Edge edge -> w.varint(0)
                    .varint(Integer.toUnsignedLong(edge.channel()))
                    .varint(Integer.toUnsignedLong(edge.consumer()));
            case OutputTarget.Plugin plugin -> w.varint(1).string(plugin.channel());
        }
        w.optional(output.key(), Postcard.Writer::string).optionalVarint(output.eventTime());
        w.vec(output.metadata(), ActivationCodec::encodeMetadataPair);
        w.bytes(output.payload());
    }

    static Output decodeOutput(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        OutputTarget target =
                switch (discriminant) {
                    case 0 -> new OutputTarget.Edge(Math.toIntExact(r.varint()), Math.toIntExact(r.varint()));
                    case 1 -> new OutputTarget.Plugin(r.string());
                    default -> throw new IllegalArgumentException(
                            "postcard: unknown OutputTarget discriminant " + discriminant);
                };
        return new Output(
                target,
                optionalString(r),
                r.optionalVarint(),
                r.vec(m -> Map.entry(m.string(), m.string())),
                r.bytes());
    }

    private static void encodePrepareResult(Postcard.Writer w, PrepareResult result) {
        switch (result) {
            case PrepareResult.Ok ok -> {
                w.varint(0).vec(ok.answer().values(), ActivationCodec::encodePreparedValue);
                w.optionalVarint(ok.answer().eventTime());
            }
            case PrepareResult.Err err -> {
                w.varint(1);
                encodeWorkError(w, err.error());
            }
        }
    }

    private static void encodePreparedValue(Postcard.Writer w, PrepareAnswer.Value value) {
        w.varint(Integer.toUnsignedLong(value.consumer()))
                .vec(value.keys(), Postcard.Writer::string)
                .bytes(value.bytes());
    }

    private static void encodeWorkError(Postcard.Writer w, WorkError error) {
        w.string(error.message()).bool(error.nonRetryable());
    }

    // ------------------------------------------------------------------
    // Shared primitives
    // ------------------------------------------------------------------

    static void encodeMetadataPair(Postcard.Writer w, Map.Entry<String, String> pair) {
        w.string(pair.getKey()).string(pair.getValue());
    }

    private static Optional<String> optionalString(Postcard.Reader r) {
        return r.some() ? Optional.of(r.string()) : Optional.empty();
    }

    static TimerKind timerKind(Postcard.Reader r) {
        return variant(r, TimerKind.values());
    }

    /** A fieldless enum, read as its declaration-order discriminant. */
    private static <E extends Enum<E>> E variant(Postcard.Reader r, E[] values) {
        int ordinal = Math.toIntExact(r.varint());
        if (ordinal < 0 || ordinal >= values.length) {
            String name = values[0].getDeclaringClass().getSimpleName();
            throw new IllegalArgumentException("postcard: unknown " + name + " ordinal " + ordinal);
        }
        return values[ordinal];
    }
}
