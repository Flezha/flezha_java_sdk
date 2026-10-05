package com.flezha.sdk.runtime.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The controller-shared Cell identity framing pin. */
class CellIdTest {

    @Test
    @DisplayName("encodedBytes matches the controller framing: [rc_len][rc][sh_len][sh][pk_len:u16 BE][pk]")
    void encodedBytesMatchesControllerFraming() {
        byte[] id = new CellId("MarketFlowRelayClass", "OhlcTasklet", "M1").encodedBytes();
        assertThat(id[0]).isEqualTo((byte) 20);
        assertThat(new String(id, 1, 20, StandardCharsets.UTF_8)).isEqualTo("MarketFlowRelayClass");
        assertThat(id[21]).isEqualTo((byte) 11);
        assertThat(new String(id, 22, 11, StandardCharsets.UTF_8)).isEqualTo("OhlcTasklet");
        ByteBuffer pk = ByteBuffer.wrap(id, 33, 4).order(ByteOrder.BIG_ENDIAN);
        assertThat(pk.getShort() & 0xFFFF).isEqualTo(2);
        assertThat(new String(id, 35, 2, StandardCharsets.UTF_8)).isEqualTo("M1");
        assertThat(id.length).isEqualTo(37);
    }
}
