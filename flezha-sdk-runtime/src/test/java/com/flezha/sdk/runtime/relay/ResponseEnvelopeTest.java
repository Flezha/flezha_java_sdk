package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.runtime.RepositoryFixture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Response channel's payload, read by the core's decoder: the request and the answer through
 * the call's classes, one case of {@code test/fixtures/call_response_envelope.json}, and bytes that
 * are no envelope.
 */
class ResponseEnvelopeTest {

    private static final String FIXTURE = "test/fixtures/call_response_envelope.json";

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
    @DisplayName("the fixture's timeout case decodes to its expected fields")
    void theTimeoutCaseDecodes() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(String.join("\n", RepositoryFixture.lines(FIXTURE)));
        JsonNode timeout = null;
        for (JsonNode c : fixture.get("cases")) {
            timeout = "error_timeout".equals(c.get("name").asText()) ? c : timeout;
        }
        JsonNode expect = timeout.get("expect");

        Response<byte[], byte[]> response = ResponseEnvelope.decode(
                timeout.get("payload").asText().getBytes(StandardCharsets.UTF_8), byte[].class, byte[].class);

        assertThat(Base64.getEncoder().encodeToString(response.request()))
                .isEqualTo(expect.get("request").asText());
        assertThat(response.target()).isEqualTo(expect.get("target").asText());
        assertThat(response.created()).isEqualTo(expect.get("created").asBoolean());
        assertThat(response.answer()).isNull();
        assertThat(response.error().code()).isEqualTo(expect.get("error_code").asText());
    }

    @Test
    @DisplayName("bytes that are no envelope are the core's malformed response")
    void bytesThatAreNoEnvelopeFail() {
        assertThatThrownBy(() -> ResponseEnvelope.decode(
                        "not json".getBytes(StandardCharsets.UTF_8), String.class, String.class))
                .isInstanceOfSatisfying(
                        FlezhaException.class, e -> assertThat(e.code()).isEqualTo("call.malformed_response"));
    }

    /** The answer class of the JSON case. */
    record Usage(int tokens) {}
}
