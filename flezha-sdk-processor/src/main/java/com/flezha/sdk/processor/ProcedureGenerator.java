package com.flezha.sdk.processor;

import com.flezha.sdk.authoring.EntitiesFor;
import com.flezha.sdk.authoring.EntityKey;
import com.flezha.sdk.authoring.InitState;
import com.flezha.sdk.authoring.Procedure;
import com.flezha.sdk.authoring.StreamOperative;
import com.flezha.sdk.authoring.WorkflowOperative;
import com.flezha.sdk.processor.ProcedureModel.Declares;
import com.flezha.sdk.processor.ProcedureModel.Output;
import com.flezha.sdk.processor.ProcedureModel.StateShape;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.Trees;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Generates {@code <Name>Procedure} from a {@code @Procedure} class, and from a
 * {@code @StreamOperative} or {@code @WorkflowOperative} class also the §5 short form's
 * single-procedure {@code <Name>StreamOperative} or {@code <Name>WorkflowOperative}. Resolution
 * reads the {@code run} signature and the annotation and reports every refusal; rendering is a pure
 * function of the {@link ProcedureModel}.
 */
final class ProcedureGenerator {

    /** The three annotations a class declares itself with, one per class. */
    static final Set<Class<? extends Annotation>> DECLARATIONS =
            Set.of(Procedure.class, StreamOperative.class, WorkflowOperative.class);

    private static final String STATE_HANDLE = "com.flezha.sdk.state.StateHandle";
    private static final String PROCEDURE_CTX = "com.flezha.sdk.procedure.ProcedureCtx";
    private static final String OPERATIVE_PACKAGE = "com.flezha.sdk.operative.";

    /** Members that describe an operative rather than a procedure, refused by name on {@code @Procedure}. */
    private static final List<String> OPERATIVE_MEMBERS = List.of("kind", "sink", "input", "onFull");

    /** The entry channel's overflow policies, each its {@code OverflowPolicy} constant in lower case. */
    private static final List<String> OVERFLOW_POLICIES = List.of("drop_nothing", "drop_newest", "drop_oldest");

    private final FlezhaProcessor out;
    private final TypeMirrors mirrors;

    /** The source trees, or {@code null} on a compiler that exposes none. */
    private final Trees trees;

    ProcedureGenerator(FlezhaProcessor out, TypeMirrors mirrors, Trees trees) {
        this.out = out;
        this.mirrors = mirrors;
        this.trees = trees;
    }

    /** Writes the procedure and, for an operative annotation, the short form's operative of its kind. */
    void generate(TypeElement type) {
        Declaration d = declarationOf(type);
        if (d == null || !annotationAgrees(d)) {
            return;
        }
        ProcedureModel model = resolve(d);
        if (model == null) {
            return;
        }
        out.write(TypeMirrors.qualify(model.pkg(), model.procedureSimple()), renderProcedure(model), type);
        if (model.declares() != Declares.PROCEDURE && operativeAgrees(model, type)) {
            out.write(TypeMirrors.qualify(model.pkg(), model.operativeSimple()), renderShortFormOperative(model), type);
        }
    }

    /**
     * A class and the one declaring annotation it carries, read alike from each of the three.
     * {@code emits} is the declared output type, {@code null} when unset.
     */
    private record Declaration(
            TypeElement type,
            Declares declares,
            String id,
            String sink,
            String input,
            String key,
            String emits,
            List<String> keys,
            int capacity,
            String onFull) {

        String annotation() {
            return declares.annotation();
        }
    }

    /** The one declaring annotation a class carries, or {@code null} once refused. */
    private Declaration declarationOf(TypeElement type) {
        List<Declaration> found = new ArrayList<>();
        Procedure p = type.getAnnotation(Procedure.class);
        if (p != null) {
            found.add(new Declaration(
                    type,
                    Declares.PROCEDURE,
                    p.id(),
                    "",
                    "",
                    p.key(),
                    declaredEmits(p::emits),
                    List.of(p.keys()),
                    p.capacity(),
                    ""));
        }
        StreamOperative s = type.getAnnotation(StreamOperative.class);
        if (s != null) {
            found.add(new Declaration(
                    type,
                    Declares.STREAM,
                    s.id(),
                    s.sink(),
                    s.input(),
                    s.key(),
                    declaredEmits(s::emits),
                    List.of(s.keys()),
                    s.capacity(),
                    s.onFull()));
        }
        WorkflowOperative w = type.getAnnotation(WorkflowOperative.class);
        if (w != null) {
            found.add(new Declaration(
                    type,
                    Declares.WORKFLOW,
                    w.id(),
                    w.sink(),
                    w.input(),
                    w.key(),
                    declaredEmits(w::emits),
                    List.of(w.keys()),
                    w.capacity(),
                    w.onFull()));
        }
        if (found.size() > 1) {
            out.error(
                    type,
                    found.stream().map(Declaration::annotation).collect(Collectors.joining(" and "))
                            + ": a class declares one procedure or one operative; keep one annotation");
            return null;
        }
        return found.get(0);
    }

    /**
     * The annotation's own rules: {@code @Procedure} carries no operative member, and a §5 short
     * form writes its factory id, never derived, and declares no topology body beside its procedure
     * body. A refusal with a contract name begins with it ({@code docs/sdk/authoring_contract.md}
     * §5.2).
     */
    private boolean annotationAgrees(Declaration d) {
        if (d.declares() == Declares.PROCEDURE) {
            return declaresNoOperative(d.type());
        }
        if (d.id().isEmpty()) {
            out.error(
                    d.type(),
                    d.annotation() + " requires `id`: an operative's factory id is an address an operator"
                            + " types, so write it as `id = \"...\"` rather than deriving it from the class name");
            return false;
        }
        List<String> topology = new ArrayList<>();
        for (Declares kind : List.of(Declares.STREAM, Declares.WORKFLOW)) {
            if (mirrors.isSubtypeOf(d.type().asType(), OPERATIVE_PACKAGE + kind.simpleName)) {
                topology.add(kind.simpleName);
            }
        }
        if (topology.isEmpty()) {
            return true;
        }
        out.error(
                d.type(),
                "ShortFormWithTopology: " + d.annotation() + " makes this class's `run` a whole operative, and"
                        + " the class also implements " + String.join(" and ", topology)
                        + ", which declares a topology body; write the short form or the full form, not both");
        return false;
    }

    /**
     * {@code @Procedure} declares no {@code kind}, {@code sink}, {@code input} or {@code onFull}, so
     * the compiler drops a written one before the model sees it and names only a missing symbol. The
     * source tree still carries it, which lets the refusal name the annotations it belongs on.
     */
    private boolean declaresNoOperative(TypeElement type) {
        boolean agrees = true;
        for (String member : writtenMembers(type, Procedure.class)) {
            if (OPERATIVE_MEMBERS.contains(member)) {
                out.error(
                        type,
                        "@Procedure declares a procedure; `" + member + "` belongs to an operative declaration:"
                                + " write the short form as @StreamOperative(...) or @WorkflowOperative(...)");
                agrees = false;
            }
        }
        return agrees;
    }

    /** The member names {@code type}'s source writes on {@code annotation}; none without source trees. */
    private List<String> writtenMembers(TypeElement type, Class<? extends Annotation> annotation) {
        List<String> names = new ArrayList<>();
        if (trees == null) {
            return names;
        }
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotation.getCanonicalName())
                    && trees.getTree(type, mirror) instanceof AnnotationTree tree) {
                for (ExpressionTree argument : tree.getArguments()) {
                    if (argument instanceof AssignmentTree assignment) {
                        names.add(assignment.getVariable().toString());
                    }
                }
            }
        }
        return names;
    }

    /** The {@code run} signature half; hands the annotation half on. {@code null} once refused. */
    private ProcedureModel resolve(Declaration d) {
        ExecutableElement run = findRun(d.type());
        if (run == null) {
            out.error(d.type(), d.annotation() + " requires exactly one public method named `run`");
            return null;
        }
        List<? extends VariableElement> params = run.getParameters();
        // A trailing `ProcedureCtx` is the multi-emit shape: the body owns every
        // emit. It sits beside the value parameters, which are counted as before.
        boolean ctxParam = !params.isEmpty()
                && mirrors.erasesTo(params.get(params.size() - 1).asType(), PROCEDURE_CTX);
        int valueParams = params.size() - (ctxParam ? 1 : 0);
        if (valueParams < 1 || valueParams > 2) {
            out.error(
                    run,
                    d.annotation()
                            + " `run` must be `Output run(Input)` or `Output run(Input, StateHandle<State, Change>)`,"
                            + " each optionally followed by a `ProcedureCtx ctx` parameter");
            return null;
        }
        String pkg = mirrors.packageOf(d.type());
        Output output = resolveOutput(d, run);
        if (output == null) {
            return null;
        }
        StateShape state = resolveState(d, run, params, valueParams == 2, pkg);
        if (state == null) {
            return null;
        }
        return resolveDeclaration(d, run, params.get(0).asType(), pkg, ctxParam, valueParams == 2, output, state);
    }

    /** The annotation half: the entity rules, the emit shape, and the model. */
    private ProcedureModel resolveDeclaration(
            Declaration d,
            ExecutableElement run,
            TypeMirror inputType,
            String pkg,
            boolean ctxParam,
            boolean stateful,
            Output output,
            StateShape state) {
        ExecutableElement entitiesFor = findAnnotated(d.type(), EntitiesFor.class);
        ExecutableElement initState = findAnnotated(d.type(), InitState.class);
        if (!declarationsAgree(d, entitiesFor, initState, stateful)) {
            return null;
        }
        String outputType = resolveEmitShape(d, run, ctxParam, output);
        if (outputType == null) {
            return null;
        }
        String simple = d.type().getSimpleName().toString();
        // A short form's written id is both its factory id and its procedure's
        // name, verbatim, so its entities keep their state path when it grows
        // into the full form.
        String id = d.id().isEmpty() ? snake(simple) : d.id();
        return new ProcedureModel(
                pkg,
                d.type().getQualifiedName().toString(),
                simple,
                inputType.toString(),
                outputType,
                state.stateType(),
                state.changeType(),
                state.handleArg(),
                initState != null
                        ? "impl." + initState.getSimpleName() + "(entityKey)"
                        : "new " + state.stateType() + "()",
                entitiesFor == null ? null : entitiesFor.getSimpleName().toString(),
                d.keys(),
                d.key(),
                id,
                d.declares(),
                d.sink(),
                d.input().isEmpty() ? "input" : d.input(),
                d.onFull(),
                d.capacity(),
                stateful,
                ctxParam,
                output.emits(),
                output.optional(),
                output.emits() || d.emits() != null);
    }

    /** What the return type declares, or {@code null} once refused. */
    private Output resolveOutput(Declaration d, ExecutableElement run) {
        TypeMirror returnType = run.getReturnType();
        // `void` = no output; `Optional<O>` = emit-on-present; bare `O` = always emit.
        if (returnType.getKind() == TypeKind.VOID) {
            return new Output(false, false, "java.lang.Void");
        }
        if (!mirrors.isOptional(returnType)) {
            return new Output(true, false, returnType.toString());
        }
        TypeMirror element = mirrors.optionalElement(returnType);
        if (element == null) {
            out.error(run, d.annotation() + " `run` returning Optional must be parameterized as Optional<Output>");
            return null;
        }
        return new Output(true, true, element.toString());
    }

    /** The state types the generated procedure threads, or {@code null} once refused. */
    private StateShape resolveState(
            Declaration d,
            ExecutableElement run,
            List<? extends VariableElement> params,
            boolean stateful,
            String pkg) {
        if (!stateful) {
            return new StateShape("com.flezha.sdk.state.Stateless", "com.flezha.sdk.state.NoChange", "state");
        }
        TypeMirror p1 = params.get(1).asType();
        if (mirrors.erasesTo(p1, STATE_HANDLE)) {
            // Raw form: `run(Input, StateHandle<State, Change>)`.
            List<? extends TypeMirror> ta = mirrors.typeArguments(p1);
            if (ta.size() != 2) {
                out.error(run, "StateHandle must be parameterized as StateHandle<State, Change>");
                return null;
            }
            return new StateShape(ta.get(0).toString(), ta.get(1).toString(), "state");
        }
        // Sugar form: `run(Input, <State>Handle)` — the @State-generated
        // handle. Recover State from its name (it may be unresolved this
        // round); the handle is co-located with its @State class.
        String handleName = p1.toString();
        if (!handleName.endsWith("Handle")) {
            out.error(
                    run,
                    d.annotation() + " stateful `run` second parameter must be a @State-generated `<State>Handle` "
                            + "or `StateHandle<State, Change>`");
            return null;
        }
        String handleFqn = handleName.contains(".") || pkg.isEmpty() ? handleName : pkg + "." + handleName;
        String stateType = handleFqn.substring(0, handleFqn.length() - "Handle".length());
        return new StateShape(stateType, stateType + "Change", "new " + handleFqn + "(state)");
    }

    /** The keying and static-entity declaration rules, in the order they refuse today. */
    private boolean declarationsAgree(
            Declaration d, ExecutableElement entitiesFor, ExecutableElement initState, boolean stateful) {
        // Entity mode: static keying is the only mode a procedure can declare
        // — dynamic keying moved to the wiring.
        if (findAnnotated(d.type(), EntityKey.class) != null) {
            out.error(
                    d.type(),
                    "@EntityKey no longer exists; declare the Entity key on the wiring instead — "
                            + "`.keyBy(f)` on the stream that feeds this procedure");
            return false;
        }
        // Per-entity state init: @InitState maps the procedure key to State.
        if (initState != null && !stateful) {
            out.error(
                    d.type(),
                    "@InitState requires a stateful run (a @State handle parameter); a stateless procedure "
                            + "has no state to initialize");
            return false;
        }
        if (entitiesFor != null && d.keys().isEmpty()) {
            out.error(
                    d.type(),
                    "static entity requires " + d.annotation() + "(keys = {...}) alongside the @EntitiesFor method");
            return false;
        }
        if (!d.keys().isEmpty() && entitiesFor == null) {
            out.error(d.type(), d.annotation() + "(keys = {...}) requires a @EntitiesFor method");
            return false;
        }
        return true;
    }

    /** Reconciles {@code emits} and {@code key} with the run shape; the output type, or {@code null} once refused. */
    private String resolveEmitShape(Declaration d, ExecutableElement run, boolean ctxParam, Output output) {
        String outputType = output.type();
        if (ctxParam) {
            if (output.emits()) {
                out.error(
                        run,
                        d.annotation() + " `run` with a `ProcedureCtx` parameter makes the body responsible for every"
                                + " emit; it must return void, and `emits = Type.class` declares the output type");
                return null;
            }
            if (!d.key().isEmpty()) {
                out.error(
                        d.type(),
                        d.annotation() + "(key = ...) conflicts with a `ProcedureCtx` parameter; the body keys its own"
                                + " emits with ctx.emitKeyed(..)");
                return null;
            }
            if (d.emits() != null) {
                outputType = d.emits();
            }
        } else if (d.emits() != null) {
            out.error(
                    d.type(),
                    d.annotation() + "(emits = ...) declares the output type of a `ProcedureCtx` `run`; without that"
                            + " parameter the return type declares it");
            return null;
        }
        if (!d.key().isEmpty() && !output.emits()) {
            out.error(
                    d.type(), d.annotation() + "(key = ...) requires a non-void return type; there is no emit to key");
            return null;
        }
        return outputType;
    }

    /** The {@code <Name>Procedure} source: the interface clause, the overrides, and the {@code run} wrapper. */
    private String renderProcedure(ProcedureModel m) {
        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.procedure.Procedure;\n");
        b.append("import com.flezha.sdk.procedure.ProcedureCtx;\n");
        b.append("import com.flezha.sdk.procedure.ProcedureException;\n");
        b.append("import com.flezha.sdk.state.RestoreInitPayload;\n");
        b.append("import com.flezha.sdk.state.StateHandle;\n");
        b.append("import com.flezha.sdk.state.WallClock;\n");
        b.append("import java.util.List;\n");
        b.append("import java.util.Optional;\n\n");
        b.append("/** Generated by FlezhaProcessor from ")
                .append(m.declares().annotation())
                .append(" on ")
                .append(m.userFqn())
                .append(". */\n");
        b.append("public final class ")
                .append(m.procedureSimple())
                .append(" implements Procedure<")
                .append(m.inputType())
                .append(", ")
                .append(m.stateType())
                .append(", ")
                .append(m.outputType())
                .append(", ")
                .append(m.changeType())
                .append(", WallClock, Void> {\n\n");
        b.append("    private final ")
                .append(m.userFqn())
                .append(" impl = new ")
                .append(m.userFqn())
                .append("();\n\n");
        // The five type tokens are not generated: the `implements Procedure<…>`
        // clause above states them concretely, and the interface defaults read
        // them off it — the same path a hand-written procedure takes.
        // The node name is the model's one identity, escaped as every other
        // author-written string is.
        b.append("    @Override public String name() { return ")
                .append(mirrors.literal(m.id()))
                .append("; }\n");
        b.append("    @Override public ")
                .append(m.stateType())
                .append(" initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) { return ")
                .append(m.initBody())
                .append("; }\n");
        renderStateOverrides(b, m);
        renderEntityOverrides(b, m);
        renderRun(b, m);
        b.append("}\n");
        return b.toString();
    }

    /** {@code applyChange} + {@code kryoTypes}, which only a stateful procedure carries. */
    private static void renderStateOverrides(StringBuilder b, ProcedureModel m) {
        if (!m.stateful()) {
            return;
        }
        b.append("    @Override public void applyChange(")
                .append(m.stateType())
                .append(" state, ")
                .append(m.changeType())
                .append(" change) { ")
                .append(m.changeType())
                .append(".apply(state, change); }\n");
        b.append("    @Override public List<Class<?>> kryoTypes() { return ")
                .append(m.changeType())
                .append(".kryoTypes(); }\n");
    }

    /** The static-entity key set and the mailbox capacity, each only when declared. */
    private void renderEntityOverrides(StringBuilder b, ProcedureModel m) {
        if (m.entitiesForName() != null) {
            b.append("    @Override public List<String> entityKeys() { return List.of(")
                    .append(m.entityKeys().stream().map(mirrors::literal).collect(Collectors.joining(", ")))
                    .append("); }\n");
            b.append("    @Override public boolean entitiesForOverridden() { return true; }\n");
            b.append("    @Override public List<String> entitiesFor(")
                    .append(m.inputType())
                    .append(" input) { return impl.")
                    .append(m.entitiesForName())
                    .append("(input); }\n");
        }
        if (m.capacity() > 0) {
            b.append("    @Override public int mailboxCapacity() { return ")
                    .append(m.capacity())
                    .append("; }\n");
            b.append("    @Override public boolean mailboxCapacityOverridden() { return true; }\n");
        }
    }

    /** The wrapper {@code run}: call the author's method, then emit what it returned. */
    private static void renderRun(StringBuilder b, ProcedureModel m) {
        b.append("\n    @Override public void run(")
                .append(m.inputType())
                .append(" input, StateHandle<")
                .append(m.stateType())
                .append(", ")
                .append(m.changeType())
                .append("> state, ProcedureCtx ctx) throws ProcedureException {\n");
        String ctxArg = m.ctxParam() ? ", ctx" : "";
        String call =
                m.stateful() ? "impl.run(input, " + m.handleArg() + ctxArg + ")" : "impl.run(input" + ctxArg + ")";
        // `key = "m"` routes the emit through emitKeyed — the only form a
        // `.keyed()` channel accepts — keeping keyed sinks reachable from the sugar.
        String emitPresent = m.emitKey().isEmpty()
                ? "ctx.emit(%s);"
                : "ctx.emitKeyed(" + m.userFqn() + "." + m.emitKey() + "(%s), %s);";
        if (!m.emits()) {
            b.append("        ").append(call).append(";\n");
        } else if (m.optional()) {
            b.append("        java.util.Optional<")
                    .append(m.outputType())
                    .append("> out = ")
                    .append(call)
                    .append(";\n");
            b.append("        if (out.isPresent()) { ")
                    .append(emitPresent.replace("%s", "out.get()"))
                    .append(" }\n");
        } else {
            b.append("        ")
                    .append(m.outputType())
                    .append(" out = ")
                    .append(call)
                    .append(";\n");
            b.append("        if (out != null) { ")
                    .append(emitPresent.replace("%s", "out"))
                    .append(" }\n");
        }
        b.append("    }\n");
    }

    /**
     * The short form's operative rules, which read the resolved body: a {@code sink} needs an output
     * to feed, and {@code onFull} names one of the entry channel's overflow policies.
     */
    private boolean operativeAgrees(ProcedureModel m, TypeElement type) {
        if (!m.sink().isEmpty() && !m.hasOutput()) {
            out.error(
                    type,
                    "SinkWithoutOutput: a `sink` requires an output type to feed the plugin: a non-void"
                            + " return, or `emits = Type.class` on the context-parameter shape");
            return false;
        }
        if (!m.onFull().isEmpty() && !OVERFLOW_POLICIES.contains(m.onFull())) {
            out.error(
                    type,
                    m.declares().annotation() + " onFull must be one of: " + String.join(", ", OVERFLOW_POLICIES));
            return false;
        }
        return true;
    }

    /**
     * The §5 short form's operative: one entry channel into this procedure and, with a
     * {@code sink}, this procedure to that plugin sink, with every other operative method at its
     * interface default. The two kinds differ only in the interface they state and the method they
     * fill, because both author on the same builder.
     */
    private String renderShortFormOperative(ProcedureModel m) {
        String operativeInterface = m.declares().simpleName;
        String declaration = m.declares() == Declares.WORKFLOW ? "procedures" : "channels";

        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.channel.ChannelBuilder;\n");
        b.append("import com.flezha.sdk.operative.").append(operativeInterface).append(";\n\n");
        b.append("/** Generated single-procedure short-form operative for ")
                .append(m.procedureSimple())
                .append(". */\n");
        b.append("public final class ")
                .append(m.operativeSimple())
                .append(" implements ")
                .append(operativeInterface)
                .append(" {\n");
        b.append("    @Override public String factoryId() { return ")
                .append(mirrors.literal(m.id()))
                .append("; }\n");
        b.append("    @Override public void ").append(declaration).append("(ChannelBuilder c) {\n");
        b.append("        c.input(")
                .append(TypeMirrors.classLit(m.inputType()))
                .append(", ")
                .append(mirrors.literal(m.input()))
                .append(")");
        if (!m.onFull().isEmpty()) {
            b.append(".onFull(com.flezha.sdk.channel.OverflowPolicy.")
                    .append(m.onFull().toUpperCase(Locale.ROOT))
                    .append(")");
        }
        b.append(".into(").append(m.procedureSimple()).append(".class)");
        if (!m.sink().isEmpty()) {
            b.append(".sink(").append(mirrors.literal(m.sink())).append(")");
        }
        b.append(";\n    }\n}\n");
        return b.toString();
    }

    /**
     * The {@code emits = Type.class} output declaration, or {@code null} when
     * unset. Reading a {@code Class} member during annotation processing throws
     * {@link MirroredTypeException} — the mirror it carries is the answer.
     */
    private static String declaredEmits(Supplier<Class<?>> emits) {
        TypeMirror mirror;
        try {
            Class<?> declared = emits.get();
            return declared == void.class ? null : declared.getCanonicalName();
        } catch (MirroredTypeException e) {
            mirror = e.getTypeMirror();
        }
        return mirror.getKind() == TypeKind.VOID ? null : mirror.toString();
    }

    private static ExecutableElement findRun(TypeElement procedure) {
        ExecutableElement found = null;
        for (Element e : procedure.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD
                    && e.getSimpleName().contentEquals("run")
                    && e.getModifiers().contains(Modifier.PUBLIC)
                    && !e.getModifiers().contains(Modifier.STATIC)) {
                if (found != null) {
                    return null;
                }
                found = (ExecutableElement) e;
            }
        }
        return found;
    }

    private static ExecutableElement findAnnotated(TypeElement procedure, Class<? extends Annotation> ann) {
        for (Element e : procedure.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && e.getAnnotation(ann) != null) {
                return (ExecutableElement) e;
            }
        }
        return null;
    }

    private static String snake(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
