package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.runtime.RepositoryFixture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Response channel's payload: every case of {@code test/fixtures/call_response_envelope.json}
 * decodes to the same fields here as in every other SDK.
 */
class ResponseEnvelopeTest {

    private static final String FIXTURE = "test/fixtures/call_response_envelope.json";

    @Test
    @DisplayName("every envelope case of the shared fixture decodes to its expected fields")
    void everyEnvelopeCaseDecodes() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(String.join("\n", RepositoryFixture.lines(FIXTURE)));
        assertThat(fixture.get("cases")).isNotEmpty();
        for (JsonNode c : fixture.get("cases")) {
            String name = c.get("name").asText();
            JsonNode expect = c.get("expect");
            Response<byte[], byte[]> response = ResponseEnvelope.decode(
                    c.get("payload").asText().getBytes(StandardCharsets.UTF_8), byte[].class, byte[].class);

            assertThat(base64(response.request()))
                    .as(name)
                    .isEqualTo(expect.get("request").asText());
            assertThat(response.target())
                    .as(name)
                    .isEqualTo(expect.get("target").asText());
            assertThat(response.created())
                    .as(name)
                    .isEqualTo(expect.get("created").asBoolean());
            assertThat(response.ok()).as(name).isEqualTo(expect.get("ok").asBoolean());
            if (response.ok()) {
                assertThat(base64(response.answer()))
                        .as(name)
                        .isEqualTo(expect.get("value").asText());
                continue;
            }
            assertThat(response.answer()).as(name).isNull();
            assertThat(response.error().code())
                    .as(name)
                    .isEqualTo(expect.get("error_code").asText());
            assertThat(response.error().errorClass())
                    .as(name)
                    .isEqualTo(expect.get("error_class").asText());
            assertThat(response.error().getMessage())
                    .as(name)
                    .isEqualTo(expect.get("error_code").asText() + ": "
                            + expect.get("error_message").asText());
        }
    }

    @Test
    @DisplayName("a text request and a JSON answer decode to their classes")
    void aTextRequestAndAJsonAnswerDecodeToTheirClasses() {
        byte[] envelope = ("{\"call\":\"c\",\"target\":\"anna-42\",\"created\":true,"
                        + "\"request\":\"YW5uYS00MjpoZWxsbw==\",\"value\":\"eyJ0b2tlbnMiOjgxMn0=\"}")
                .getBytes(StandardCharsets.UTF_8);

        Response<String, Usage> response = ResponseEnvelope.decode(envelope, String.class, Usage.class);

        assertThat(response.request()).isEqualTo("anna-42:hello");
        assertThat(response.answer()).isEqualTo(new Usage(812));
    }

    @Test
    @DisplayName("an error's details and hint are the error document's")
    void anErrorsDetailsAndHintAreTheDocuments() {
        byte[] envelope = ("{\"call\":\"c\",\"target\":\"bob-7\",\"created\":false,\"request\":\"aGk=\","
                        + "\"error\":{\"code\":\"relay.not_found\",\"class\":\"not_found\",\"message\":\"gone\","
                        + "\"details\":{\"relay\":\"bob-7\"},\"hint\":\"flz relay list\"}}")
                .getBytes(StandardCharsets.UTF_8);

        Response<String, String> response = ResponseEnvelope.decode(envelope, String.class, String.class);

        assertThat(response.error().details()).containsEntry("relay", "bob-7");
        assertThat(response.error().hint()).contains("flz relay list");
    }

    @Test
    @DisplayName("bytes that are no envelope fail as a bad entry parse does")
    void bytesThatAreNoEnvelopeFail() {
        assertThatThrownBy(() -> ResponseEnvelope.decode(
                        "not json".getBytes(StandardCharsets.UTF_8), String.class, String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The answer class of the JSON case. */
    record Usage(int tokens) {}

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
