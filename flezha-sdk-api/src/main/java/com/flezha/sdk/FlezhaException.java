package com.flezha.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flezha.sdk.internal.RuntimeBridge;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one exception this SDK throws for anything Flezha refuses: an administration call, a spawn
 * read, a connect or start, a registration, and a runtime misuse such as an emit on a channel the
 * tasklet does not own.
 *
 * <p>Branch on {@link #code()}, a stable {@code family.snake} string such as
 * {@code topology.duplicate_factory_id} or {@code quota.exceeded}, or on {@link #errorClass()}, one
 * of {@code internal}, {@code invalid}, {@code unreachable}, {@code denied}, {@code not_found},
 * {@code conflict} and {@code unavailable}; never on the message text. {@link #getMessage()} is the
 * code followed by the catalogue's sentence, and {@link #details()} holds the values that sentence
 * names.
 *
 * <p>A refusal the controller or the core made carries their rendering. A refusal this SDK makes
 * itself is rendered by the core, through the native module.
 */
public final class FlezhaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UNCATALOGUED = "internal.uncatalogued";

    private final String code;
    private final String errorClass;
    private final String sentence;
    private final transient Map<String, Object> details;

    @Nullable
    private final String hint;

    private FlezhaException(JsonNode error, @Nullable Throwable cause) {
        super(
                error.path("code").asText()
                        + (error.path("message").isTextual()
                                ? ": " + error.path("message").asText()
                                : ""),
                cause);
        this.code = error.path("code").asText();
        this.errorClass = error.path("class").asText();
        this.sentence = error.path("message").asText();
        this.details = Collections.unmodifiableMap(
                JSON.convertValue(error.path("details"), new TypeReference<Map<String, Object>>() {}));
        JsonNode hintNode = error.path("hint");
        this.hint = hintNode.isTextual() ? hintNode.asText() : null;
    }

    /** The error {@code code} names, its catalogue sentence filled from {@code details}. */
    @Nonnull
    public static FlezhaException of(@Nonnull String code, @Nonnull Map<String, ?> details) {
        return of(code, details, null);
    }

    /** {@link #of(String, Map)}, keeping the failure that caused it. */
    @Nonnull
    public static FlezhaException of(@Nonnull String code, @Nonnull Map<String, ?> details, @Nullable Throwable cause) {
        return new FlezhaException(Renderer.render(code, JSON.valueToTree(details)), cause);
    }

    /**
     * Several refusals of one declaration as one exception, the way the core reports a topology
     * check: the first refusal's code and details, with {@code details.issues} listing every one as
     * {@code {code, message, details}}.
     */
    @Nonnull
    public static FlezhaException ofIssues(@Nonnull List<FlezhaException> issues) {
        FlezhaException first = issues.get(0);
        ObjectNode details = JSON.valueToTree(first.details);
        ArrayNode listed = details.putArray("issues");
        for (FlezhaException issue : issues) {
            listed.addObject()
                    .put("code", issue.code)
                    .put("message", issue.sentence)
                    .set("details", JSON.valueToTree(issue.details));
        }
        return new FlezhaException(Renderer.render(first.code, details), null);
    }

    /**
     * The error a JSON error document ({@code {"error": {...}}}) carries, as the controller and the
     * core render it. Text that is not one is carried as {@code internal.uncatalogued}, naming it.
     */
    @Nonnull
    public static FlezhaException fromDocument(@Nonnull String document) {
        try {
            JsonNode error = JSON.readTree(document).path("error");
            if (error.path("code").isTextual() && error.path("class").isTextual()) {
                return new FlezhaException(error, null);
            }
        } catch (JsonProcessingException notADocument) {
            // Carried as uncatalogued below.
        }
        return of(UNCATALOGUED, Map.of("reason", document));
    }

    /** The stable {@code family.snake} code. */
    @Nonnull
    public String code() {
        return code;
    }

    /** One of the seven class words: what kind of failure this is, and so what a caller can do. */
    @Nonnull
    public String errorClass() {
        return errorClass;
    }

    /** The values the message names, as the JSON object the error document carries. */
    @Nonnull
    public Map<String, Object> details() {
        return details;
    }

    /** The next command to run, when there is one. */
    @Nonnull
    public Optional<String> hint() {
        return Optional.ofNullable(hint);
    }

    /**
     * The code of every refusal a declaration refusal lists in {@code details.issues}, in the order
     * they fired; this exception's own code when it lists none.
     */
    @Nonnull
    public List<String> issueCodes() {
        if (!(details.get("issues") instanceof List<?> issues)) {
            return List.of(code);
        }
        List<String> codes = new ArrayList<>(issues.size());
        for (Object issue : issues) {
            if (issue instanceof Map<?, ?> map && map.get("code") instanceof String issueCode) {
                codes.add(issueCode);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * The core's renderer, reached through the native module when it is on the class path. Without
     * it, the API module alone, an error carries its code and details, its message is the code and
     * its class {@code internal}.
     */
    private static final class Renderer {

        private static final String CORE = "com.flezha.sdk.native_.FlzCore";
        private static final boolean PRESENT = RuntimeBridge.present(CORE);
        private static final Class<?>[] SIGNATURE = {String.class, String.class};

        static JsonNode render(String code, JsonNode details) {
            if (!PRESENT) {
                ObjectNode error = JSON.createObjectNode().put("code", code).put("class", "internal");
                return error.set("details", details);
            }
            String document = (String) RuntimeBridge.invoke(
                    CORE, "errorDocument", SIGNATURE, "the core rendered no " + code, code, details.toString());
            try {
                return JSON.readTree(document).path("error");
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
