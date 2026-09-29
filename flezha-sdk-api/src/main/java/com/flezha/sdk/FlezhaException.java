package com.flezha.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
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
 * itself is rendered from the catalogue the core renders from, {@code flz_transport/errors.json},
 * packaged with this module: the JNI boundary exposes no renderer of its own.
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
        super(error.path("code").asText() + ": " + error.path("message").asText(), cause);
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
        return new FlezhaException(Catalogue.render(code, JSON.valueToTree(details)), cause);
    }

    /**
     * Several refusals of one declaration as one exception, the way the core reports a topology
     * check: the first refusal's code and details, with {@code details.issues} listing every one as
     * {@code {code, message, details}}.
     */
    @Nonnull
    public static FlezhaException ofIssues(@Nonnull List<FlezhaException> issues) {
        FlezhaException first = issues.get(0);
        ObjectNode error = Catalogue.render(first.code, JSON.valueToTree(first.details));
        ArrayNode listed = ((ObjectNode) error.get("details")).putArray("issues");
        for (FlezhaException issue : issues) {
            listed.addObject()
                    .put("code", issue.code)
                    .put("message", issue.sentence)
                    .set("details", JSON.valueToTree(issue.details));
        }
        return new FlezhaException(error, null);
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

    /** The catalogue and the renderer the core's {@code flz_transport::errors} holds, byte for byte. */
    private static final class Catalogue {

        private static final JsonNode CODES = load().path("codes");

        private static JsonNode load() {
            try (InputStream in = FlezhaException.class.getResourceAsStream("errors.json")) {
                if (in == null) {
                    throw new IllegalStateException("errors.json is not packaged beside FlezhaException");
                }
                return JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** {@code {code, class, message, details, hint}}; an unknown code is uncatalogued, naming it. */
        static ObjectNode render(String code, JsonNode details) {
            JsonNode entry = CODES.path(code);
            if (entry.isMissingNode()) {
                ObjectNode named = details.isObject() ? ((ObjectNode) details).deepCopy() : JSON.createObjectNode();
                return render(UNCATALOGUED, named.put("reason", code));
            }
            ObjectNode error = JSON.createObjectNode();
            error.put("code", code);
            error.put("class", entry.path("class").asText());
            error.put("message", template(entry.path("message").asText(), details));
            error.set("details", details.isObject() ? details : JSON.createObjectNode());
            JsonNode hint = entry.path("hint");
            if (hint.isTextual()) {
                error.put("hint", template(hint.asText(), details));
            } else {
                error.putNull("hint");
            }
            return error;
        }

        /**
         * {@code template} with every {@code {key}} replaced by {@code details[key]} as text: a
         * string as written, a list of strings joined by {@code ", "}, anything else as compact
         * JSON. A key the details do not carry is left as written.
         */
        static String template(String template, JsonNode details) {
            StringBuilder out = new StringBuilder(template.length());
            int at = 0;
            while (at < template.length()) {
                int open = template.indexOf('{', at);
                int close = open < 0 ? -1 : template.indexOf('}', open + 1);
                if (open < 0 || close < 0) {
                    out.append(template, at, template.length());
                    break;
                }
                String key = template.substring(open + 1, close);
                if (!isKey(key)) {
                    out.append(template, at, open + 1);
                    at = open + 1;
                    continue;
                }
                out.append(template, at, open);
                JsonNode value = details.get(key);
                out.append(value == null ? template.substring(open, close + 1) : text(value));
                at = close + 1;
            }
            return out.toString();
        }

        private static boolean isKey(String word) {
            return !word.isEmpty()
                    && word.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_');
        }

        private static String text(JsonNode value) {
            if (value.isTextual()) {
                return value.asText();
            }
            if (value.isArray() && allText(value)) {
                List<String> items = new ArrayList<>(value.size());
                value.forEach(item -> items.add(item.asText()));
                return String.join(", ", items);
            }
            return sorted(value);
        }

        private static boolean allText(JsonNode array) {
            for (JsonNode item : array) {
                if (!item.isTextual()) {
                    return false;
                }
            }
            return true;
        }

        /** Compact JSON with every object's keys in byte order, as the core writes it. */
        private static String sorted(JsonNode value) {
            if (value.isObject()) {
                List<String> keys = new ArrayList<>();
                value.fieldNames().forEachRemaining(keys::add);
                Collections.sort(keys);
                StringBuilder out = new StringBuilder("{");
                for (int i = 0; i < keys.size(); i++) {
                    out.append(i > 0 ? "," : "")
                            .append(JSON.valueToTree(keys.get(i)).toString());
                    out.append(':').append(sorted(value.get(keys.get(i))));
                }
                return out.append('}').toString();
            }
            if (value.isArray()) {
                List<String> items = new ArrayList<>();
                value.forEach(item -> items.add(sorted(item)));
                return "[" + String.join(",", items) + "]";
            }
            return value.toString();
        }
    }
}
