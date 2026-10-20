package com.flezha.sdk.runtime.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Saved state decodes after a built-in leaves the codec. The bytes below were written by the codec
 * while {@code CoalesceKey} held the second built-in id; its reserved slot keeps {@code long[]}, the
 * built-ins after it and a tasklet's listed types at the ids those bytes carry.
 */
class KryoCodecGoldenTest {

    /** A cell's state holding a built-in registered after the reserved slot. */
    public static final class Samples {
        public Object values;
        public String label;
    }

    /** A change record over it. */
    public static final class Added {
        public Object values;
    }

    private static final byte[] SNAPSHOT = HexFormat.of().parseHex("21676f6c6465ee1004060d808080808040");
    private static final byte[] CHANGE = HexFormat.of().parseHex("221003161a");

    private final KryoCodec codec = new KryoCodec(List.of(Samples.class, Added.class));

    @Test
    @DisplayName("a snapshot written before the slot was reserved decodes to the state written")
    void aSavedSnapshotDecodes() {
        Samples state = codec.deserialize(SNAPSHOT, Samples.class);

        assertThat(state.label).isEqualTo("golden");
        assertThat(state.values).isEqualTo(new long[] {3L, -7L, 1L << 40});
    }

    @Test
    @DisplayName("a change record written before the slot was reserved decodes to the change written")
    void aSavedChangeDecodes() {
        Added change = codec.deserialize(CHANGE, Added.class);

        assertThat(change.values).isEqualTo(new long[] {11L, 13L});
    }
}
