package com.flezha.sdk.runtime.relay.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * A channel's format and JSON Schema as its relay's API announces them, read off the declared
 * payload type once, when the class is built.
 *
 * <p>Only a record derives a schema, recursively over its components: strings, numbers,
 * booleans, enums, lists, sets and arrays, {@code Map<String, V>} and nested records. An
 * {@code Optional} or {@code @Nullable} component is not required. A component of any other type
 * accepts any value, and a top-level type that is not a record gives no schema: derivation never
 * refuses a class.
 */
final class DerivedSchema {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Set<Class<?>> INTEGERS = Set.of(
            int.class,
            long.class,
            short.class,
            byte.class,
            Integer.class,
            Long.class,
            Short.class,
            Byte.class,
            BigInteger.class);

    private DerivedSchema() {}

    /** The format a value of {@code type} takes when no codec is named: the plain encoding's. */
    static String plainCodec(Class<?> type) {
        if (type == byte[].class) {
            return "bytes";
        }
        return type == String.class ? "text" : "json";
    }

    /** The schema a record type derives, or {@code null} for any other type. */
    @Nullable
    static JsonNode of(Class<?> type) {
        return type.isRecord() ? record(type, new HashSet<>()) : null;
    }

    /**
     * A declared schema as JSON. Text that does not parse is kept as a JSON string, which the
     * core refuses with {@code topology.schema_invalid} like any other schema that is not an
     * object.
     */
    static JsonNode explicit(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return NODES.textNode(json);
        }
    }

    private static ObjectNode record(Class<?> type, Set<Class<?>> open) {
        // A record that contains itself reads as any value one level down.
        if (!open.add(type)) {
            return NODES.objectNode();
        }
        ObjectNode schema = NODES.objectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = NODES.arrayNode();
        for (RecordComponent component : type.getRecordComponents()) {
            properties.set(component.getName(), schemaOf(component.getGenericType(), open));
            if (component.getType() != Optional.class && !nullable(component)) {
                required.add(component.getName());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        open.remove(type);
        return schema;
    }

    private static ObjectNode schemaOf(Type type, Set<Class<?>> open) {
        if (type instanceof ParameterizedType parameterized) {
            return parameterized(parameterized, open);
        }
        if (type instanceof GenericArrayType array) {
            return arrayOf(schemaOf(array.getGenericComponentType(), open));
        }
        if (!(type instanceof Class<?> c)) {
            return NODES.objectNode();
        }
        if (c == String.class || c == char.class || c == Character.class || c == byte[].class) {
            return typed("string");
        }
        if (c == boolean.class || c == Boolean.class) {
            return typed("boolean");
        }
        if (INTEGERS.contains(c)) {
            return typed("integer");
        }
        if (c == double.class || c == float.class || Number.class.isAssignableFrom(c)) {
            return typed("number");
        }
        if (c.isEnum()) {
            ObjectNode schema = typed("string");
            ArrayNode names = schema.putArray("enum");
            for (Object constant : c.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return schema;
        }
        if (c.isArray()) {
            return arrayOf(schemaOf(c.getComponentType(), open));
        }
        return c.isRecord() ? record(c, open) : NODES.objectNode();
    }

    private static ObjectNode parameterized(ParameterizedType type, Set<Class<?>> open) {
        Type[] args = type.getActualTypeArguments();
        if (!(type.getRawType() instanceof Class<?> raw)) {
            return NODES.objectNode();
        }
        if (raw == Optional.class) {
            return schemaOf(args[0], open);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arrayOf(schemaOf(args[0], open));
        }
        if (Map.class.isAssignableFrom(raw) && args[0] == String.class) {
            ObjectNode schema = typed("object");
            schema.set("additionalProperties", schemaOf(args[1], open));
            return schema;
        }
        return NODES.objectNode();
    }

    private static ObjectNode arrayOf(ObjectNode items) {
        ObjectNode schema = typed("array");
        schema.set("items", items);
        return schema;
    }

    private static ObjectNode typed(String type) {
        return NODES.objectNode().put("type", type);
    }

    /** Any annotation named {@code Nullable}, whichever library declares it. */
    private static boolean nullable(RecordComponent component) {
        return Stream.concat(
                        Stream.of(component.getAnnotations()),
                        Stream.of(component.getAnnotatedType().getAnnotations()))
                .map(Annotation::annotationType)
                .anyMatch(annotation -> annotation.getSimpleName().equals("Nullable"));
    }
}
