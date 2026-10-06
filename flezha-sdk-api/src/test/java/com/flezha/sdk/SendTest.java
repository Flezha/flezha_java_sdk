package com.flezha.sdk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.flezha.sdk.runtime.FlezhaImpl;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code runner.send} and {@code runner.call}: what each hands the core, and how it reads the answer. */
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
        FlezhaImpl.ADMIN_CALLS.clear();
        FlezhaImpl.ADMIN_ANSWER = "null";
    }

    record Order(String pair, int amount) {}

    record Placed(String pair, int amount, String status) {}

    @Test
    @DisplayName("a call sends the value as an output encodes it and reads the answer by its format")
    void aCallReadsTheAnswerByItsFormat() {
        FlezhaImpl.ADMIN_ANSWER =
                "{\"answer\":{\"pair\":\"EUR/USD\",\"amount\":1000,\"status\":\"placed\"},\"answer_codec\":\"json\"}";
        assertThat(runner.call("orders", "place", new Order("EUR/USD", 1000), Placed.class))
                .isEqualTo(new Placed("EUR/USD", 1000, "placed"));

        FlezhaImpl.ADMIN_ANSWER = "{\"answer\":\"done\",\"answer_codec\":\"text\"}";
        assertThat(runner.call("orders", "audit", "checked", String.class)).isEqualTo("done");

        FlezhaImpl.ADMIN_ANSWER = "{\"answer\":\"AQI=\",\"answer_codec\":\"bytes\"}";
        assertThat(runner.call("orders", "raw", new byte[] {9}, byte[].class)).containsExactly(1, 2);

        FlezhaImpl.ADMIN_ANSWER = "{\"accepted\":true}";
        assertThat(runner.call("orders", "audit", "checked", JsonNode.class)
                        .path("accepted")
                        .asBoolean())
                .isTrue();

        String order = Base64.getEncoder().encodeToString("{\"pair\":\"EUR/USD\",\"amount\":1000}".getBytes(UTF_8));
        String text = Base64.getEncoder().encodeToString("checked".getBytes(UTF_8));
        String payload = "{\"payload\":\"base64\"}";
        assertThat(FlezhaImpl.ADMIN_CALLS)
                .containsExactly(
                        List.of("Relay.call_channel", "[\"orders\",\"place\",\"" + order + "\"," + payload + "]"),
                        List.of("Relay.call_channel", "[\"orders\",\"audit\",\"" + text + "\"," + payload + "]"),
                        List.of("Relay.call_channel", "[\"orders\",\"raw\",\"CQ==\"," + payload + "]"),
                        List.of("Relay.call_channel", "[\"orders\",\"audit\",\"" + text + "\"," + payload + "]"));
    }

    @Test
    @DisplayName("an answer that does not read as the asked type is refused by name")
    void anUnreadableAnswerIsRefused() {
        FlezhaImpl.ADMIN_ANSWER = "{\"answer\":\"done\",\"answer_codec\":\"text\"}";

        assertThatThrownBy(() -> runner.call("orders", "audit", "checked", Placed.class))
                .isInstanceOf(FlezhaException.class)
                .extracting(e -> ((FlezhaException) e).code())
                .isEqualTo("control.unreadable_answer");
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
