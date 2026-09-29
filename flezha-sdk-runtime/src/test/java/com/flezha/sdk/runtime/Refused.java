package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flezha.sdk.FlezhaException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** A refusal asserted by its code, never by its message. */
public final class Refused {

    private Refused() {}

    /** The {@link FlezhaException} {@code call} throws, after asserting it names {@code code}. */
    public static FlezhaException by(String code, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(FlezhaException.class);
        FlezhaException refusal = (FlezhaException) thrown;
        assertThat(refusal.issueCodes()).as(refusal.getMessage()).contains(code);
        return refusal;
    }

    /**
     * The details of the refusal named {@code code}: the exception's own, or those of the issue in
     * {@code details.issues} that carries it.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> details(FlezhaException refusal, String code) {
        if (refusal.details().get("issues") instanceof List<?> issues) {
            for (Object issue : issues) {
                Map<String, Object> listed = (Map<String, Object>) issue;
                if (code.equals(listed.get("code"))) {
                    return (Map<String, Object>) listed.get("details");
                }
            }
        }
        assertThat(refusal.code()).isEqualTo(code);
        return refusal.details();
    }
}
