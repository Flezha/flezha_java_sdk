package com.flezha.sdk.channel;

import com.flezha.sdk.FlezhaException;
import javax.annotation.Nullable;

/**
 * The outcome of one {@link OutputChain#call call}, arriving as an input on the caller's Response
 * channel.
 *
 * <p>Exactly one of {@code answer} and {@code error} is set: {@link #ok()} tells which. A call the
 * callee did not answer in time ends in the error {@code call.timeout}.
 *
 * @param request the request as the call end wrote it, decoded back as the chain's payload type
 * @param answer the callee's answer, decoded as {@code input} decodes the answer class; {@code null}
 *     when the call failed
 * @param error what ended the call, {@code null} when it was answered
 * @param target the callee's instance id, a generated one included
 * @param created whether this call started the callee
 */
public record Response<Req, Ans>(
        Req request, @Nullable Ans answer, @Nullable FlezhaException error, String target, boolean created) {

    /** Whether the callee answered; {@link #answer()} then holds the answer. */
    public boolean ok() {
        return error == null;
    }
}
