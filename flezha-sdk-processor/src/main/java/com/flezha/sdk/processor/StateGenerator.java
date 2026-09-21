package com.flezha.sdk.processor;

import com.flezha.sdk.authoring.Journal;
import com.flezha.sdk.processor.StateModel.Field;
import com.flezha.sdk.processor.StateModel.Variant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Generates {@code <Name>Change} and {@code <Name>Handle} from a {@code @State}
 * class. Resolution reads the fields and reports every refusal; rendering is a
 * pure function of the {@link StateModel}, so a variant's shape is stated once
 * and emitted once.
 */
final class StateGenerator {

    private final FlezhaProcessor out;
    private final TypeMirrors mirrors;

    /** Each generated handle's state class, which a nested state's name alone does not give. */
    private final Map<String, String> stateByHandle = new HashMap<>();

    StateGenerator(FlezhaProcessor out, TypeMirrors mirrors) {
        this.out = out;
        this.mirrors = mirrors;
    }

    void generate(TypeElement state) {
        StateModel model = resolve(state);
        if (model == null) {
            return;
        }
        out.write(model.changeFqn(), renderChange(model), state);
        out.write(model.handleFqn(), renderHandle(model), state);
        stateByHandle.put(model.handleFqn(), model.stateFqn());
    }

    /** The state class behind a handle this generator wrote, or {@code null} for any other. */
    String stateOf(String handleFqn) {
        return stateByHandle.get(handleFqn);
    }

    /** The fields and the variants they contribute, or {@code null} once refused. */
    private StateModel resolve(TypeElement state) {
        List<VariableElement> fields = mutableFields(state);
        if (fields == null) {
            return null;
        }
        List<Variant> variants = new ArrayList<>();
        for (VariableElement f : fields) {
            if (!resolveField(f, variants)) {
                return null;
            }
        }
        String pkg = mirrors.packageOf(state);
        String changeSimple = state.getSimpleName() + "Change";
        String handleSimple = state.getSimpleName() + "Handle";
        return new StateModel(
                pkg,
                state.getQualifiedName().toString(),
                changeSimple,
                TypeMirrors.qualify(pkg, changeSimple),
                handleSimple,
                TypeMirrors.qualify(pkg, handleSimple),
                variants);
    }

    /** The public, non-static, non-final fields, or {@code null} once refused. */
    private List<VariableElement> mutableFields(TypeElement state) {
        List<VariableElement> fields = new ArrayList<>();
        for (Element e : state.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && !e.getModifiers().contains(Modifier.STATIC)) {
                if (!e.getModifiers().contains(Modifier.PUBLIC)
                        || e.getModifiers().contains(Modifier.FINAL)) {
                    out.error(
                            e,
                            "@State requires public, non-final fields for field-granular change "
                                    + "derivation; a conditional, multi-field, or computed mutation needs a "
                                    + "hand-written Change");
                    return null;
                }
                fields.add((VariableElement) e);
            }
        }
        if (fields.isEmpty()) {
            out.error(state, "@State requires at least one mutable field");
            return null;
        }
        return fields;
    }

    /** Appends one field's variants in emission order; {@code false} once refused. */
    private boolean resolveField(VariableElement f, List<Variant> variants) {
        TypeMirror[] kv = mirrors.mapKV(f.asType());
        Journal journal = f.getAnnotation(Journal.class);
        if (journal != null) {
            return journalVariants(f, journal.value(), kv, variants);
        }
        // A map field without @Journal is an unsupported mutation: there is
        // no single per-entry task to record. Direct to a hand-written Change.
        if (kv != null) {
            out.error(
                    f,
                    "@State: a map field needs a @Journal(MAP_SET | MAP_VEC | MAP_DEQUE) annotation to "
                            + "derive its per-key journaling mutators; hand-write a Change for anything richer");
            return false;
        }
        flatVariants(f, variants);
        return true;
    }

    /** {@code Set}, plus {@code Push} for a {@code List} and the sliding pair for a {@code Deque}. */
    private void flatVariants(VariableElement f, List<Variant> variants) {
        String fname = f.getSimpleName().toString();
        String pascal = pascal(fname);
        variants.add(StateModel.value(
                "Set" + pascal, "set" + pascal, f.asType().toString(), "state." + fname + " = c.value(); return;"));

        TypeMirror elem = mirrors.listElement(f.asType());
        if (elem != null) {
            variants.add(StateModel.value(
                    "Push" + pascal, "push" + pascal, elem.toString(), "state." + fname + ".add(c.value()); return;"));
        }

        // PushBack<Field>(E) + PopFront<Field>(int) for Deque<E> — the same
        // deltas MAP_DEQUE records, for a window whose cell key already is
        // what the map would have keyed by. Without them a per-cell window
        // could only journal whole-collection sets.
        TypeMirror dequeElem = mirrors.dequeElement(f.asType());
        if (dequeElem != null) {
            variants.add(StateModel.value(
                    "PushBack" + pascal,
                    "pushBack" + pascal,
                    dequeElem.toString(),
                    "state." + fname + ".addLast(c.value()); return;"));
            variants.add(StateModel.count(
                    "PopFront" + pascal,
                    "popFront" + pascal,
                    "var __d = state." + fname + "; int __n = Math.min(c.n(), __d.size()); "
                            + "for (int __i = 0; __i < __n; __i++) { __d.pollFirst(); } return;"));
        }
    }

    /** The per-key mutators a {@code @Journal} map field contributes; {@code false} once refused. */
    private boolean journalVariants(VariableElement f, Journal.Mode mode, TypeMirror[] kv, List<Variant> variants) {
        if (kv == null) {
            out.error(f, "@Journal requires a Map<K, V> field; hand-write a Change for anything richer");
            return false;
        }
        String fname = f.getSimpleName().toString();
        String pascal = pascal(fname);
        String keyType = kv[0].toString();
        return switch (mode) {
            case MAP_SET -> {
                variants.add(StateModel.keyed(
                        "Set" + pascal,
                        "set" + pascal,
                        keyType,
                        kv[1].toString(),
                        "state." + fname + ".put(c.key(), c.value()); return;"));
                yield true;
            }
            case MAP_VEC -> mapVecVariants(f, fname, pascal, keyType, kv[1], variants);
            case MAP_DEQUE -> mapDequeVariants(f, fname, pascal, keyType, kv[1], variants);
        };
    }

    /** {@code push} / {@code replaceLast} / {@code drainFront} over a {@code Map<K, List<U>>}. */
    private boolean mapVecVariants(
            VariableElement f, String fname, String pascal, String keyType, TypeMirror val, List<Variant> variants) {
        TypeMirror elem = mirrors.listElement(val);
        if (elem == null) {
            out.error(
                    f,
                    "@Journal(MAP_VEC) requires a Map<K, List<U>> field; hand-write a Change for " + "anything richer");
            return false;
        }
        String u = elem.toString();
        String at = "state." + fname + ".computeIfAbsent(c.key(), __k -> new java.util.ArrayList<>())";
        variants.add(StateModel.keyed("Push" + pascal, "push" + pascal, keyType, u, at + ".add(c.value()); return;"));
        variants.add(StateModel.keyed(
                "ReplaceLast" + pascal,
                "replaceLast" + pascal,
                keyType,
                u,
                "var __l = " + at + "; if (__l.isEmpty()) { __l.add(c.value()); } else { "
                        + "__l.set(__l.size() - 1, c.value()); } return;"));
        variants.add(StateModel.keyedCount(
                "Drain" + pascal + "Front",
                "drain" + pascal + "Front",
                keyType,
                "var __l = " + at + "; int __n = Math.min(c.n(), __l.size()); __l.subList(0, __n).clear(); return;"));
        return true;
    }

    /** {@code pushBack} / {@code popFront} over a {@code Map<K, Deque<U>>}. */
    private boolean mapDequeVariants(
            VariableElement f, String fname, String pascal, String keyType, TypeMirror val, List<Variant> variants) {
        TypeMirror elem = mirrors.dequeElement(val);
        if (elem == null) {
            out.error(
                    f,
                    "@Journal(MAP_DEQUE) requires a Map<K, Deque<U>> field; hand-write a Change for "
                            + "anything richer");
            return false;
        }
        String u = elem.toString();
        String at = "state." + fname + ".computeIfAbsent(c.key(), __k -> new java.util.ArrayDeque<>())";
        variants.add(StateModel.keyed(
                "PushBack" + pascal, "pushBack" + pascal, keyType, u, at + ".addLast(c.value()); return;"));
        variants.add(StateModel.keyedCount(
                "PopFront" + pascal,
                "popFront" + pascal,
                keyType,
                "var __d = " + at + "; int __n = Math.min(c.n(), __d.size()); "
                        + "for (int __i = 0; __i < __n; __i++) { __d.pollFirst(); } return;"));
        return true;
    }

    /**
     * The {@code <Name>Change} source: a factory and a variant record per entry,
     * then {@code apply}. A sealed interface of records rather than a class
     * hierarchy, because the journal is read back through Kryo, which builds a
     * record from its canonical constructor and anything else only from a no-arg
     * one. It is also the shape a hand-written Change already has.
     */
    private static String renderChange(StateModel m) {
        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.state.Change;\n");
        b.append("import java.util.List;\n\n");
        b.append("/** Generated by FlezhaProcessor from @State on ")
                .append(m.stateFqn())
                .append(". */\n");
        b.append("public sealed interface ").append(m.changeSimple()).append(" extends Change {\n");
        for (Variant v : m.variants()) {
            b.append(factory(m.changeSimple(), v)).append(variantRecord(m.changeSimple(), v));
        }
        applyMethod(b, m);
        kryoTypesMethod(b, m);
        b.append("}\n");
        return b.toString();
    }

    /** The {@code apply} fold: one {@code instanceof} arm per variant, in declaration order. */
    private static void applyMethod(StringBuilder b, StateModel m) {
        b.append("    /** Fold a change into the state. */\n");
        b.append("    static void apply(")
                .append(m.stateFqn())
                .append(" state, ")
                .append(m.changeSimple())
                .append(" change) {\n");
        for (Variant v : m.variants()) {
            b.append("        if (change instanceof ")
                    .append(v.name())
                    .append(" c) { ")
                    .append(v.applyBody())
                    .append(" }\n");
        }
        b.append("    }\n\n");
    }

    private static void kryoTypesMethod(StringBuilder b, StateModel m) {
        b.append("    /** Kryo type registration for the state + change variants. */\n");
        b.append("    static List<Class<?>> kryoTypes() {\n");
        b.append("        return List.of(")
                .append(m.stateFqn())
                .append(".class, ")
                .append(m.changeSimple())
                .append(".class");
        for (Variant v : m.variants()) {
            b.append(", ").append(v.name()).append(".class");
        }
        b.append(");\n    }\n");
    }

    /** The {@code <Name>Handle} source: the journaling handle a sugared {@code run} receives. */
    private static String renderHandle(StateModel m) {
        String handle = "StateHandle<" + m.stateFqn() + ", " + m.changeSimple() + ">";
        StringBuilder h = FlezhaProcessor.source(m.pkg());
        h.append("import com.flezha.sdk.state.StateHandle;\n\n");
        h.append("/** Journaling handle for ").append(m.stateFqn()).append(", generated by @State. */\n");
        h.append("public final class ").append(m.handleSimple()).append(" {\n");
        h.append("    private final ").append(handle).append(" h;\n");
        h.append("    public ")
                .append(m.handleSimple())
                .append("(")
                .append(handle)
                .append(" h) { this.h = h; }\n");
        h.append("    public ").append(m.stateFqn()).append(" get() { return h.get(); }\n");
        h.append("    public void apply(").append(m.changeSimple()).append(" change) { h.apply(change); }\n");
        for (Variant v : m.variants()) {
            h.append(handleMutator(m.changeSimple(), v));
        }
        h.append("}\n");
        return h.toString();
    }

    /** {@code static <Change> <method>(<params>) { return new <Variant>(<args>); }} */
    private static String factory(String changeSimple, Variant v) {
        return "    static " + changeSimple + " " + v.method() + "(" + paramList(v) + ") { return new " + v.name() + "("
                + argList(v) + "); }\n";
    }

    /** {@code record <Variant>(<components>) implements <Change> {}} */
    private static String variantRecord(String changeSimple, Variant v) {
        return "    record " + v.name() + "(" + paramList(v) + ") implements " + changeSimple + " {}\n\n";
    }

    /** {@code public void <method>(<params>) { h.apply(<Change>.<method>(<args>)); }} */
    private static String handleMutator(String changeSimple, Variant v) {
        return "    public void " + v.method() + "(" + paramList(v) + ") { h.apply(" + changeSimple + "." + v.method()
                + "(" + argList(v) + ")); }\n";
    }

    /** {@code K key, V value} — the record's components, and the factory and mutator parameters. */
    private static String paramList(Variant v) {
        return v.fields().stream().map(f -> f.type() + " " + f.name()).collect(Collectors.joining(", "));
    }

    /** {@code key, value} — what the factory hands the canonical constructor. */
    private static String argList(Variant v) {
        return v.fields().stream().map(Field::name).collect(Collectors.joining(", "));
    }

    private static String pascal(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
