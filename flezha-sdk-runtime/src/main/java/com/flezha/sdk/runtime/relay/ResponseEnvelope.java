package com.flezha.sdk.runtime.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.channel.Response;
import java.io.IOException;
import java.util.Base64;
import javax.annotation.Nonnull;

/**
 * The payload of a Response channel: the JSON envelope the controller writes for each call
 * outcome, read into a {@link Response}. The request and the answer travel as standard base64 and
 * are decoded as an entry channel decodes its payload.
 */
public final class ResponseEnvelope {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponseEnvelope() {}

    /** One envelope; bytes that are not one fail as an unchecked error, as a bad entry parse does. */
    @Nonnull
    public static <Q, A> Response<Q, A> decode(
            @Nonnull byte[] bytes, @Nonnull Class<Q> request, @Nonnull Class<A> answer) {
        JsonNode envelope;
        try {
            envelope = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Response channel: the envelope is not JSON: " + e.getMessage(), e);
        }
        if (!envelope.path("target").isTextual()) {
            throw new IllegalArgumentException("Response channel: the envelope names no target");
        }
        Q decodedRequest = PlainPayload.decode(base64(envelope, "request"), request);
        String target = envelope.get("target").asText();
        boolean created = envelope.path("created").asBoolean(false);
        JsonNode error = envelope.get("error");
        if (error != null && error.isObject()) {
            FlezhaException failure = FlezhaException.fromDocument(
                    JSON.createObjectNode().set("error", error).toString());
            return new Response<>(decodedRequest, null, failure, target, created);
        }
        A value = PlainPayload.decode(base64(envelope, "value"), answer);
        return new Response<>(decodedRequest, value, null, target, created);
    }

    private static byte[] base64(JsonNode envelope, String field) {
        JsonNode text = envelope.get(field);
        if (text == null || !text.isTextual()) {
            throw new IllegalArgumentException("Response channel: the envelope carries no " + field);
        }
        return Base64.getDecoder().decode(text.asText());
    }
}
