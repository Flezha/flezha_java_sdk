package com.flezha.sdk.runtime.core;

import com.flezha.sdk.runtime.state.Postcard;
import com.flezha.sdk.runtime.state.TimerOp;
import java.util.Optional;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * The inverses of {@link ActivationCodec}'s hot-path directions, which only the
 * tests need: encode an Activation as the core hands it out, decode an
 * ActivationResult as the core reads it.
 */
public final class ActivationCodecInverses {

    private ActivationCodecInverses() {}

    @Nonnull
    public static byte[] encodeActivation(@Nonnull Activation a) {
        Postcard.Writer w = new Postcard.Writer();
        w.varint(a.token());
        encodeCellRef(w, a.target());
        encodeKind(w, a.kind());
        w.varint(a.changeSeq()).string(a.factory());
        return w.toBytes();
    }

    @Nonnull
    public static ActivationResult decodeResult(@Nonnull byte[] bytes) {
        Postcard.Reader r = new Postcard.Reader(bytes);
        ActivationResult result = new ActivationResult(r.varint(), decodeOutcome(r));
        if (!r.exhausted()) {
            throw new IllegalArgumentException("postcard: trailing bytes after ActivationResult at " + r.pos());
        }
        return result;
    }

    private static void encodeCellRef(Postcard.Writer w, CellRef ref) {
        w.string(ref.relay()).varint(Integer.toUnsignedLong(ref.tasklet()));
        w.optional(ref.cellKey(), Postcard.Writer::string);
    }

    private static void encodeRestoreBody(Postcard.Writer w, ActivationKind.RestoreBody body) {
        w.bytes(body.bytes());
        w.optionalVarint(
                body.fromVersion().isPresent()
                        ? OptionalLong.of(body.fromVersion().getAsInt())
                        : OptionalLong.empty());
    }

    private static void encodeKind(Postcard.Writer w, ActivationKind kind) {
        switch (kind) {
            case ActivationKind.Work work -> w.varint(0).vec(work.items(), ActivationCodecInverses::encodeWorkItem);
            case ActivationKind.RestoreFold fold -> {
                w.varint(1)
                        .optional(fold.snapshot(), ActivationCodecInverses::encodeRestoreBody)
                        .vec(fold.changes(), ActivationCodecInverses::encodeRestoreBody);
            }
            case ActivationKind.SnapshotRequest ignored -> w.varint(2);
            case ActivationKind.LiveStateRead ignored -> w.varint(3);
            case ActivationKind.ApplyState apply -> w.varint(4).bytes(apply.stateJson());
            case ActivationKind.MigrationSerialize ignored -> w.varint(5);
            case ActivationKind.KeyRequest request -> {
                w.varint(6).varint(Integer.toUnsignedLong(request.channel()));
                w.vec(request.payloads(), Postcard.Writer::bytes)
                        .bool(request.wantKeys())
                        .bool(request.wantEventTime());
            }
            case ActivationKind.CompensateRun comp -> w.varint(7).vec(comp.outputs(), Postcard.Writer::bytes);
            case ActivationKind.Leave leave -> w.varint(8).bool(leave.wholeRelay());
        }
    }

    private static void encodeWorkItem(Postcard.Writer w, WorkItem item) {
        switch (item) {
            case WorkItem.Input input -> {
                w.varint(0).varint(Integer.toUnsignedLong(input.channel())).bytes(input.payload());
                w.vec(input.metadata(), ActivationCodec::encodeMetadataPair);
                w.optionalVarint(input.consumedOffset()).bool(input.replay()).varint(input.now());
            }
            case WorkItem.Timer timer -> w.varint(1)
                    .varint(timer.kind().ordinal())
                    .varint(timer.scheduledAt());
        }
    }

    private static ActivationOutcome decodeOutcome(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        return switch (discriminant) {
            case 0 -> new ActivationOutcome.Work(r.vec(ActivationCodecInverses::decodeItemEffect));
            case 1 -> new ActivationOutcome.Restored();
            case 2 -> new ActivationOutcome.Snapshot(r.bytes());
            case 3 -> new ActivationOutcome.StateJson(r.bytes());
            case 4 -> new ActivationOutcome.StateApplied();
            case 5 -> new ActivationOutcome.MigrationState(r.bytes());
            case 6 -> new ActivationOutcome.Prepared(r.vec(ActivationCodecInverses::decodePrepareResult));
            case 7 -> new ActivationOutcome.Failed(decodeWorkError(r));
            default -> throw new IllegalArgumentException(
                    "postcard: unknown ActivationOutcome discriminant " + discriminant);
        };
    }

    private static ItemEffect decodeItemEffect(Postcard.Reader r) {
        return new ItemEffect(
                decodeVerdict(r),
                r.vec(Postcard.Reader::bytes),
                r.vec(ActivationCodec::decodeOutput),
                r.optionalVarint(),
                r.varint(),
                r.vec(m -> new TimerOp(ActivationCodec.timerKind(m), m.varint(), m.bool())),
                r.bool(),
                r.some()
                        ? Optional.of(
                                new DlqEntry(r.string(), r.string(), Math.toIntExact(r.varint()), r.optionalBytes()))
                        : Optional.empty(),
                r.optionalBytes());
    }

    private static Verdict decodeVerdict(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        return switch (discriminant) {
            case 0 -> new Verdict.Ok();
            case 1 -> new Verdict.RetryableError(r.string());
            case 2 -> new Verdict.NonRetryableError(r.string());
            case 3 -> new Verdict.Unreadable(r.string());
            default -> throw new IllegalArgumentException("postcard: unknown Verdict discriminant " + discriminant);
        };
    }

    private static PrepareResult decodePrepareResult(Postcard.Reader r) {
        int discriminant = Math.toIntExact(r.varint());
        return switch (discriminant) {
            case 0 -> new PrepareResult.Ok(new PrepareAnswer(r.vec(Postcard.Reader::string), r.optionalVarint()));
            case 1 -> new PrepareResult.Err(decodeWorkError(r));
            default -> throw new IllegalArgumentException(
                    "postcard: unknown PrepareResult discriminant " + discriminant);
        };
    }

    private static WorkError decodeWorkError(Postcard.Reader r) {
        return new WorkError(r.string(), r.bool());
    }
}
