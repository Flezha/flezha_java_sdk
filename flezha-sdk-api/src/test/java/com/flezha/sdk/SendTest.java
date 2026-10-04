package com.flezha.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.FlezhaImpl;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code runner.send}: what it hands the core, and how it reads the answer. */
class SendTest {

    private FlezhaRunner runner;

    @BeforeEach
    void start() {
        runner = Flezha.createRunner("send_test").start();
    }

    @AfterEach
    void reset() {
        runner.close();
        FlezhaImpl.SENDS.clear();
        FlezhaImpl.SEND_ANSWER = "null";
    }

    @Test
    @DisplayName("a send hands its options to the core and answers the verdict")
    void aSendHandsItsOptionsToTheCore() {
        FlezhaImpl.SEND_ANSWER = "{\"to\":\"orders\",\"verdict\":\"absorbed\"}";

        assertThat(runner.send("orders", "o-41", "in").path("verdict").asText()).isEqualTo("absorbed");
        runner.send("orders", "o-41", "in", SendOpts.of().msgId("orders/0/41").key("EURUSD"));

        assertThat(FlezhaImpl.SENDS)
                .containsExactly(
                        Arrays.asList("orders", "in", "o-41", null, null, false),
                        Arrays.asList("orders", "in", "o-41", "orders/0/41", "EURUSD", false));
    }

    @Test
    @DisplayName("a local send is marked local and answers null")
    void aLocalSendAnswersNull() {
        assertThat(runner.send("orders", "o-41", "in", SendOpts.of().local())).isNull();

        assertThat(FlezhaImpl.SENDS).containsExactly(Arrays.asList("orders", "in", "o-41", null, null, true));
    }
}
