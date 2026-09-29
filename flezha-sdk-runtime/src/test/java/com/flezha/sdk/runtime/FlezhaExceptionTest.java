package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one exception's rendering, held to the contract set's {@code error_rendering.json}, the
 * cases the controller's and the core's renderers are held to byte for byte.
 */
class FlezhaExceptionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a code renders the catalogue's message, class and hint, as the core renders them")
    void aCodeRendersAsTheCoreRendersIt() throws Exception {
        JsonNode cases =
                JSON.readTree(String.join("\n", RepositoryFixture.lines("test/fixtures/error_rendering.json")));
        assertThat(cases).isNotEmpty();
        for (JsonNode rendering : cases) {
            Map<String, Object> details =
                    JSON.convertValue(rendering.path("details"), new TypeReference<Map<String, Object>>() {});
            JsonNode expected = JSON.readTree(rendering.path("json").asText()).path("error");

            FlezhaException rendered = FlezhaException.of(rendering.path("code").asText(), details);

            assertThat(rendered.code()).isEqualTo(expected.path("code").asText());
            assertThat(rendered.errorClass()).isEqualTo(expected.path("class").asText());
            assertThat(rendered.getMessage())
                    .isEqualTo(expected.path("code").asText() + ": "
                            + expected.path("message").asText());
            assertThat(rendered.hint().orElse(null))
                    .isEqualTo(
                            expected.path("hint").isNull()
                                    ? null
                                    : expected.path("hint").asText());
            assertThat(rendered.details()).isEqualTo(details);
        }
    }

    @Test
    @DisplayName("an error document reads back as the error it carries")
    void anErrorDocumentReadsBack() {
        FlezhaException read = FlezhaException.fromDocument("{\"error\":{\"code\":\"quota.exceeded\","
                + "\"class\":\"conflict\",\"message\":\"the namespace's max_operators quota is reached (3 of 3)\","
                + "\"details\":{\"current\":3,\"kind\":\"max_operators\",\"limit\":3},"
                + "\"hint\":\"flz namespace show\"}}");

        assertThat(read.code()).isEqualTo("quota.exceeded");
        assertThat(read.errorClass()).isEqualTo("conflict");
        assertThat(read.getMessage())
                .isEqualTo("quota.exceeded: the namespace's max_operators quota is reached (3 of 3)");
        assertThat(read.details()).containsEntry("limit", 3).containsEntry("kind", "max_operators");
        assertThat(read.hint()).contains("flz namespace show");
    }

    @Test
    @DisplayName("text that is not a document, and a code the catalogue lacks, are uncatalogued naming it")
    void unknownsAreUncatalogued() {
        assertThat(FlezhaException.fromDocument("activation copy failed").code())
                .isEqualTo("internal.uncatalogued");
        assertThat(FlezhaException.fromDocument("activation copy failed").details())
                .containsEntry("reason", "activation copy failed");

        FlezhaException unknown = FlezhaException.of("topology.not_a_code", Map.of("tasklet", "t"));
        assertThat(unknown.code()).isEqualTo("internal.uncatalogued");
        assertThat(unknown.details())
                .containsEntry("reason", "topology.not_a_code")
                .containsEntry("tasklet", "t");
    }

    @Test
    @DisplayName("several refusals are one exception listing every code, the first one's leading")
    void severalRefusalsAreOneException() {
        FlezhaException both = FlezhaException.ofIssues(List.of(
                FlezhaException.of("topology.two_clocks", Map.of("tasklet", "a")),
                FlezhaException.of("topology.tasklet_name_missing", Map.of("tasklet", "B"))));

        assertThat(both.code()).isEqualTo("topology.two_clocks");
        assertThat(both.issueCodes()).containsExactly("topology.two_clocks", "topology.tasklet_name_missing");
        assertThat(FlezhaException.of("topology.two_clocks", Map.of("tasklet", "a"))
                        .issueCodes())
                .containsExactly("topology.two_clocks");
    }
}
