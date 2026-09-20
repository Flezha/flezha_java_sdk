package com.flezha.sdk.processor;

import java.util.List;

/**
 * What a {@code @State} class resolves to: the two sources to write, and one
 * entry per generated {@code Change} variant. Every string is already the text
 * the renderer emits, so rendering reads no {@code javax.lang.model} type.
 */
record StateModel(
        String pkg,
        String stateFqn,
        String changeSimple,
        String changeFqn,
        String handleSimple,
        String handleFqn,
        List<Variant> variants) {

    /**
     * One generated {@code Change} variant: the nested record, the static factory
     * and handle mutator that mint it, and the body of its {@code apply} arm.
     */
    record Variant(String name, String method, List<Field> fields, String applyBody) {}

    /**
     * One component of a variant record: its type and the one name it carries —
     * on the record, in the canonical constructor, and in the factory and mutator
     * that mint it. That name is the accessor an author reads, so it is spelled
     * as a hand-written Change spells it: {@code key}, {@code value}, {@code n}.
     */
    record Field(String type, String name) {}

    /** {@code (T value)} — one whole value the author supplies. */
    static Variant value(String name, String method, String type, String applyBody) {
        return new Variant(name, method, List.of(new Field(type, "value")), applyBody);
    }

    /** {@code (K key, V value)} — a per-key map mutation. */
    static Variant keyed(String name, String method, String keyType, String valType, String applyBody) {
        return new Variant(name, method, List.of(new Field(keyType, "key"), new Field(valType, "value")), applyBody);
    }

    /** {@code (K key, int n)} — a count-based mutation on one key's collection. */
    static Variant keyedCount(String name, String method, String keyType, String applyBody) {
        return new Variant(name, method, List.of(new Field(keyType, "key"), new Field("int", "n")), applyBody);
    }

    /** {@code (int n)} — a count-based mutation on a flat collection. */
    static Variant count(String name, String method, String applyBody) {
        return new Variant(name, method, List.of(new Field("int", "n")), applyBody);
    }
}
