package com.flezha.sdk.runtime.relay.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The JSON Schema a record payload type derives for its relay's API. */
class DerivedSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    enum Side {
        BUY,
        SELL
    }

    record Leg(String venue, double weight) {}

    record Order(
            String pair,
            int lots,
            long id,
            double amount,
            BigDecimal price,
            boolean urgent,
            Side side,
            List<String> tags,
            Set<Integer> codes,
            String[] notes,
            Map<String, Leg> legs,
            Leg main,
            Optional<String> memo,
            @Nullable String comment,
            Instant at) {}

    record Node(String name, List<Node> children) {}

    @Test
    @DisplayName("a record derives every supported component type, and leaves optional ones out of required")
    void aRecordDerivesEverySupportedType() throws Exception {
        JsonNode schema = DerivedSchema.of(Order.class);

        JsonNode leg = JSON.readTree(
                """
                {"type":"object","properties":{"venue":{"type":"string"},"weight":{"type":"number"}},
                 "required":["venue","weight"]}
                """);
        assertThat(schema.get("type").asText()).isEqualTo("object");
        JsonNode p = schema.get("properties");
        assertThat(p.get("pair")).isEqualTo(JSON.readTree("{\"type\":\"string\"}"));
        assertThat(p.get("lots")).isEqualTo(JSON.readTree("{\"type\":\"integer\"}"));
        assertThat(p.get("id")).isEqualTo(JSON.readTree("{\"type\":\"integer\"}"));
        assertThat(p.get("amount")).isEqualTo(JSON.readTree("{\"type\":\"number\"}"));
        assertThat(p.get("price")).isEqualTo(JSON.readTree("{\"type\":\"number\"}"));
        assertThat(p.get("urgent")).isEqualTo(JSON.readTree("{\"type\":\"boolean\"}"));
        assertThat(p.get("side")).isEqualTo(JSON.readTree("{\"type\":\"string\",\"enum\":[\"BUY\",\"SELL\"]}"));
        assertThat(p.get("tags")).isEqualTo(JSON.readTree("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        assertThat(p.get("codes")).isEqualTo(JSON.readTree("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}"));
        assertThat(p.get("notes")).isEqualTo(JSON.readTree("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        assertThat(p.get("legs").get("type").asText()).isEqualTo("object");
        assertThat(p.get("legs").get("additionalProperties")).isEqualTo(leg);
        assertThat(p.get("main")).isEqualTo(leg);
        assertThat(p.get("memo")).isEqualTo(JSON.readTree("{\"type\":\"string\"}"));
        assertThat(p.get("comment")).isEqualTo(JSON.readTree("{\"type\":\"string\"}"));
        assertThat(p.get("at"))
                .as("a type derivation cannot read accepts any value")
                .isEqualTo(JSON.readTree("{}"));
        assertThat(schema.get("required"))
                .isEqualTo(
                        JSON.readTree(
                                """
                        ["pair","lots","id","amount","price","urgent","side","tags","codes","notes","legs","main","at"]
                        """));
    }

    @Test
    @DisplayName("a record that contains itself reads as any value one level down")
    void aRecursiveRecordStops() throws Exception {
        JsonNode schema = DerivedSchema.of(Node.class);

        assertThat(schema.at("/properties/children")).isEqualTo(JSON.readTree("{\"type\":\"array\",\"items\":{}}"));
    }

    @Test
    @DisplayName("a type that is not a record gives no schema, and the plain codec follows the type")
    void onlyARecordDerives() {
        assertThat(DerivedSchema.of(String.class)).isNull();
        assertThat(DerivedSchema.of(Map.class)).isNull();
        assertThat(DerivedSchema.plainCodec(String.class)).isEqualTo("text");
        assertThat(DerivedSchema.plainCodec(byte[].class)).isEqualTo("bytes");
        assertThat(DerivedSchema.plainCodec(Order.class)).isEqualTo("json");
    }

    @Test
    @DisplayName("a declared schema is kept as JSON, and text that does not parse as a JSON string")
    void anExplicitSchemaIsKept() throws Exception {
        assertThat(DerivedSchema.explicit("{\"type\":\"object\"}")).isEqualTo(JSON.readTree("{\"type\":\"object\"}"));
        assertThat(DerivedSchema.explicit("{not json").asText()).isEqualTo("{not json");
    }
}
