package com.flezha.sdk.procedure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The async result of a {@link ProcedureCtx#spawnWorkflow} request, returned before the controller
 * answers. {@link #await} waits for the answer; {@link #tryResult} never waits.
 *
 * <p>Read it inside the invocation that made it. Every read asks the core, which answers a resolved
 * handle the same way until that invocation ends. Then the core releases the handle and logs a
 * failure no read collected, once; a read after that throws an unchecked exception naming the
 * released handle.
 */
public final class SpawnHandle {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How long after its request a result is awaited before it resolves as {@link SpawnException.Timeout}. */
    public static final long RESULT_TIMEOUT_MS = 30_000;

    /** One read of the core's handle: its answer as JSON, or {@code null} while pending. */
    @FunctionalInterface
    interface Answer {
        @Nullable
        String read(long timeoutMs);
    }

    private final Answer answer;

    SpawnHandle(Answer answer) {
        this.answer = answer;
    }

    /**
     * Wait for the controller's answer. The core bounds the wait: {@link #RESULT_TIMEOUT_MS} after the
     * request it resolves a timeout.
     */
    @Nonnull
    public SpawnResult await() throws SpawnException {
        return read(Long.MAX_VALUE)
                .orElseThrow(
                        () -> new IllegalStateException("the core answered pending to a read bounded by its deadline"));
    }

    /** The answer if it has arrived, without waiting; empty while it is pending. */
    @Nonnull
    public Optional<SpawnResult> tryResult() throws SpawnException {
        return read(0);
    }

    /** {@code {"id","outcome"}} is the result; {@code {"id","error","message"}} is thrown as its failure. */
    private Optional<SpawnResult> read(long timeoutMs) throws SpawnException {
        String json = answer.read(timeoutMs);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode error = node.get("error");
            if (error != null) {
                throw SpawnException.of(error.asText(), node.required("message").asText());
            }
            String outcome = node.required("outcome").asText().toUpperCase(Locale.ROOT);
            return Optional.of(new SpawnResult(node.required("id").asText(), SpawnOutcome.valueOf(outcome)));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("the core answered a spawn this SDK cannot read: " + json, e);
        }
    }
}
