package com.flezha.sdk.tasklet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The async result of a {@link Ctx#spawnSteps} request, returned before the controller
 * answers. {@link #await} waits for the answer; {@link #tryResult} never waits.
 *
 * <p>A request that reached no instance throws {@link FlezhaException} from the read, with the
 * controller's code ({@code relay.already_running}, {@code relay.factory_unknown},
 * {@code relay.factory_not_steps}, or any other refusal's own) or the core's
 * ({@code spawn.timeout}, {@code spawn.unavailable}).
 *
 * <p>Read it inside the invocation that made it. Every read asks the core, which answers a resolved
 * handle the same way until that invocation ends. Then the core releases the handle and logs a
 * failure no read collected, once; a read after that throws {@code runtime.spawn_handle_released}.
 */
public final class SpawnHandle {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One read of the core's handle: its answer as JSON, or {@code null} while pending; a failure throws. */
    @FunctionalInterface
    interface Answer {
        @Nullable
        String read(long timeoutMs);
    }

    private final Answer answer;

    SpawnHandle(Answer answer) {
        this.answer = answer;
    }

    /** Wait for the controller's answer, to the core's bound: at the request's deadline it resolves a timeout. */
    @Nonnull
    public SpawnResult await() {
        return read(Long.MAX_VALUE)
                .orElseThrow(
                        () -> new IllegalStateException("the core answered pending to a read bounded by its deadline"));
    }

    /** The answer if it has arrived, without waiting; empty while it is pending. */
    @Nonnull
    public Optional<SpawnResult> tryResult() {
        return read(0);
    }

    /** {@code {"id","outcome"}} is the result. */
    private Optional<SpawnResult> read(long timeoutMs) {
        String json = answer.read(timeoutMs);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(json);
            String outcome = node.required("outcome").asText().toUpperCase(Locale.ROOT);
            return Optional.of(new SpawnResult(node.required("id").asText(), SpawnOutcome.valueOf(outcome)));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("the core answered a spawn this SDK cannot read: " + json, e);
        }
    }
}
