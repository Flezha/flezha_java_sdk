package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.native_.FlzCore;
import com.flezha.sdk.runtime.state.Postcard;
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
        Postcard.Reader parts = new Postcard.Reader(FlzCore.responseDecode(bytes));
        String target = parts.string();
        boolean created = parts.bool();
        Q decodedRequest = PlainPayload.decode(parts.bytes(), request);
        if (parts.varint() == 0) {
            A value = PlainPayload.decode(parts.bytes(), answer);
            return new Response<>(decodedRequest, value, null, target, created);
        }
        FlezhaException failure = FlezhaException.fromDocument(parts.string());
        return new Response<>(decodedRequest, null, failure, target, created);
    }
}
