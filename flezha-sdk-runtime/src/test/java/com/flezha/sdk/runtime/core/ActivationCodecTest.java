package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.RepositoryFixture;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.state.TimerKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-language byte pin: the values {@code flz_core} encodes into the rows of {@code
 * test/fixtures/postcard_bodies.txt} must produce the same bytes here and decode back to the same
 * values. A mismatch means the schema changed — bump {@code SCHEMA_VERSION} on both sides.
 */
class ActivationCodecTest {

    private static final String FIXTURE = "test/fixtures/postcard_bodies.txt";

    private static String pinned(String type, String caseName) throws IOException {
        return RepositoryFixture.value(FIXTURE, type, caseName);
    }

    private static Activation workActivation() {
        return new Activation(
                42,
                new CellRef("op-1", 3, Optional.of("k")),
                new ActivationKind.Work(List.of(
                        new WorkItem.Input(
                                1,
                                new byte[] {(byte) 0xAA, (byte) 0xBB},
                                List.of(Map.entry("flz.offset", "7")),
                                OptionalLong.of(7),
                                true,
                                1_000),
                        new WorkItem.Timer(TimerKind.EVENT_TIME, 2_000))),
                9,
                "f");
    }

    private static ActivationResult resultFixture() {
        return resultFixture(Optional.empty());
    }

    private static ActivationResult resultFixture(Optional<byte[]> complete) {
        return new ActivationResult(
                42,
                new ActivationOutcome.Work(List.of(new ItemEffect(
                        new Verdict.Ok(),
                        List.of(new byte[] {0x01}),
                        List.of(new Output(
                                new OutputTarget.Plugin("out"),
                                Optional.of("k2"),
                                OptionalLong.empty(),
                                List.of(),
                                new byte[] {0x02})),
                        OptionalLong.of(7),
                        1_000,
                        List.of(TimerOp.cancel(TimerKind.PROCESSING_TIME, 2_000)),
                        true,
                        Optional.empty(),
                        complete))));
    }

    @Test
    @DisplayName("the codec speaks the shared fixture's schema version")
    void schemaVersionIsTheFixtures() throws IOException {
        assertThat(FlzCore.SCHEMA_VERSION)
                .isEqualTo(Integer.parseInt(
                        RepositoryFixture.value("test/fixtures/wire_constants.txt", "schema_version", "activation")));
    }

    @Test
    @DisplayName("work activation bytes are pinned to the shared fixture")
    void workActivationBytesArePinned() throws IOException {
        byte[] encoded = ActivationCodec.encodeActivation(workActivation());
        assertThat(hex(encoded)).isEqualTo(pinned("activation", "work"));
        assertThat(ActivationCodec.decodeActivation(encoded))
                .usingRecursiveComparison()
                .isEqualTo(workActivation());
    }

    @Test
    @DisplayName("result bytes are pinned to the shared fixture")
    void resultBytesArePinned() throws IOException {
        byte[] encoded = ActivationCodec.encodeResult(resultFixture());
        assertThat(hex(encoded)).isEqualTo(pinned("result", "work"));
        assertThat(ActivationCodec.decodeResult(encoded))
                .usingRecursiveComparison()
                .isEqualTo(resultFixture());
    }

    @Test
    @DisplayName("a completion request is the effect's last field")
    void completionIsTheEffectsLastField() throws IOException {
        ActivationResult completing = resultFixture(Optional.of("done".getBytes(StandardCharsets.UTF_8)));
        byte[] encoded = ActivationCodec.encodeResult(completing);

        assertThat(hex(encoded)).isEqualTo(pinned("result", "completing"));
        assertThat(ActivationCodec.decodeResult(encoded))
                .usingRecursiveComparison()
                .isEqualTo(completing);
    }

    @Test
    @DisplayName("command activation kinds are pinned and round-trip")
    void commandKindBytesArePinned() throws IOException {
        Map<String, ActivationKind> kinds = Map.ofEntries(
                Map.entry(
                        "restore_fold",
                        new ActivationKind.RestoreFold(
                                Optional.of(new ActivationKind.RestoreBody(new byte[] {0x0F}, OptionalInt.empty())),
                                List.of(
                                        new ActivationKind.RestoreBody(new byte[] {0x01}, OptionalInt.empty()),
                                        new ActivationKind.RestoreBody(new byte[] {0x02}, OptionalInt.of(1))))),
                Map.entry("snapshot_request", new ActivationKind.SnapshotRequest()),
                Map.entry("live_state_read", new ActivationKind.LiveStateRead()),
                Map.entry("apply_state", new ActivationKind.ApplyState("{}".getBytes(StandardCharsets.UTF_8))),
                Map.entry("migration_serialize", new ActivationKind.MigrationSerialize()),
                Map.entry("key_request", new ActivationKind.KeyRequest(2, List.of(new byte[] {0x70}), true, false)),
                Map.entry("compensate_run", new ActivationKind.CompensateRun(List.of(new byte[] {0x0A}))),
                Map.entry("leave", new ActivationKind.Leave(true)));
        for (Map.Entry<String, ActivationKind> kind : kinds.entrySet()) {
            Activation act = new Activation(1, new CellRef("o", 0, Optional.empty()), kind.getValue(), 0, "f");
            byte[] bytes = ActivationCodec.encodeActivation(act);
            assertThat(hex(bytes)).as(kind.getKey()).isEqualTo(pinned("activation", kind.getKey()));
            assertThat(ActivationCodec.decodeActivation(bytes))
                    .usingRecursiveComparison()
                    .isEqualTo(act);
        }
    }

    @Test
    @DisplayName("every outcome variant round-trips through encode/decode")
    void outcomeVariantsRoundTrip() {
        List<ActivationOutcome> outcomes = List.of(
                new ActivationOutcome.Restored(),
                new ActivationOutcome.Snapshot(new byte[] {0x05}),
                new ActivationOutcome.StateJson("{\"a\":1}".getBytes()),
                new ActivationOutcome.StateApplied(),
                new ActivationOutcome.MigrationState("{}".getBytes()),
                new ActivationOutcome.Prepared(List.of(
                        new PrepareResult.Ok(new PrepareAnswer(List.of("k1", "k2"), OptionalLong.of(77))),
                        new PrepareResult.Err(new WorkError("bad key", true)))),
                new ActivationOutcome.Failed(new WorkError("boom", false)),
                new ActivationOutcome.Work(List.of(new ItemEffect(
                        new Verdict.RetryableError("later"),
                        List.of(),
                        List.of(new Output(
                                new OutputTarget.Edge(3, 4),
                                Optional.empty(),
                                OptionalLong.of(500),
                                List.of(Map.entry("m", "v")),
                                new byte[] {0x01})),
                        OptionalLong.empty(),
                        0,
                        List.of(TimerOp.register(TimerKind.EVENT_TIME, 9_000)),
                        false,
                        Optional.of(new DlqEntry("ch", "poison", 3, Optional.of(new byte[] {0x0C}))),
                        Optional.empty()))),
                new ActivationOutcome.Work(List.of(new ItemEffect(
                        new Verdict.NonRetryableError("never"),
                        List.of(new byte[] {0x02}),
                        List.of(),
                        OptionalLong.of(1),
                        2,
                        List.of(),
                        false,
                        Optional.empty(),
                        Optional.of(new byte[] {0x09})))));
        for (ActivationOutcome outcome : outcomes) {
            ActivationResult result = new ActivationResult(7, outcome);
            byte[] bytes = ActivationCodec.encodeResult(result);
            ActivationResult decoded = ActivationCodec.decodeResult(bytes);
            assertThat(decoded).usingRecursiveComparison().isEqualTo(result);
            assertThat(ActivationCodec.encodeResult(decoded)).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("the unreadable verdict is discriminant 3 on the wire")
    void unreadableVerdictIsDiscriminantThree() {
        ActivationResult result = new ActivationResult(
                1,
                new ActivationOutcome.Work(List.of(new ItemEffect(
                        new Verdict.Unreadable("bad"),
                        List.of(),
                        List.of(),
                        OptionalLong.empty(),
                        0,
                        List.of(),
                        false,
                        Optional.empty(),
                        Optional.empty()))));
        byte[] bytes = ActivationCodec.encodeResult(result);
        // token 1, outcome Work (0), one effect, verdict 3, message "bad".
        assertThat(hex(bytes)).startsWith("01" + "00" + "01" + "03" + "03" + "626164");
        assertThat(ActivationCodec.decodeResult(bytes))
                .usingRecursiveComparison()
                .isEqualTo(result);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
