package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.native_.FlzCore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.annotation.Nonnull;

/**
 * The payload of a Response channel: the envelope the controller writes for each call outcome, read
 * by the core's decoder into a {@link Response}. The request and the answer are decoded as an entry
 * channel decodes its payload.
 */
public final class ResponseEnvelope {

    private ResponseEnvelope() {}

    /** One envelope; bytes that are not one throw {@code call.malformed_response}. */
    @Nonnull
    public static <Q, A> Response<Q, A> decode(
            @Nonnull byte[] bytes, @Nonnull Class<Q> request, @Nonnull Class<A> answer) {
        int[] parts = new int[5];
        byte[] decoded = FlzCore.responseDecode(bytes, parts);
        int targetEnd = parts[0];
        int requestEnd = targetEnd + parts[1];
        String target = new String(decoded, 0, targetEnd, StandardCharsets.UTF_8);
        boolean created = parts[3] == 1;
        Q decodedRequest = PlainPayload.decode(Arrays.copyOfRange(decoded, targetEnd, requestEnd), request);
        if (parts[4] == 1) {
            A value = PlainPayload.decode(Arrays.copyOfRange(decoded, requestEnd, decoded.length), answer);
            return new Response<>(decodedRequest, value, null, target, created);
        }
        FlezhaException failure = FlezhaException.fromDocument(
                new String(decoded, requestEnd, decoded.length - requestEnd, StandardCharsets.UTF_8));
        return new Response<>(decodedRequest, null, failure, target, created);
    }
}
