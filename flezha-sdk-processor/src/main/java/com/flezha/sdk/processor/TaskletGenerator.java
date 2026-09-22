package com.flezha.sdk.processor;

import com.flezha.sdk.authoring.CellKey;
import com.flezha.sdk.authoring.CellsFor;
import com.flezha.sdk.authoring.InitState;
import com.flezha.sdk.authoring.StreamOperative;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.authoring.WorkflowOperative;
import com.flezha.sdk.processor.TaskletModel.Declares;
import com.flezha.sdk.processor.TaskletModel.Output;
import com.flezha.sdk.processor.TaskletModel.StateShape;
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
 * Generates {@code <Name>Tasklet} from a {@code @Tasklet} class, and from a
 * {@code @StreamOperative} or {@code @WorkflowOperative} class also the §5 short form's
 * single-tasklet {@code <Name>StreamOperative} or {@code <Name>WorkflowOperative}. Resolution
 * reads the {@code run} signature and the annotation and reports every refusal; rendering is a pure
 * function of the {@link TaskletModel}.
 */
final class TaskletGenerator {

    /** The three annotations a class declares itself with, one per class. */
    static final Set<Class<? extends Annotation>> DECLARATIONS =
            Set.of(Tasklet.class, StreamOperative.class, WorkflowOperative.class);

    private static final String STATE_HANDLE = "com.flezha.sdk.state.StateHandle";
    private static final String TASKLET_CTX = "com.flezha.sdk.tasklet.TaskletCtx";
    private static final String OPERATIVE_PACKAGE = "com.flezha.sdk.operative.";

    /** Members that describe an operative rather than a tasklet, refused by name on {@code @Tasklet}. */
    private static final List<String> OPERATIVE_MEMBERS = List.of("kind", "sink", "input", "onFull");

    /** The entry channel's overflow policies, each its {@code OverflowPolicy} constant in lower case. */
    private static final List<String> OVERFLOW_POLICIES = List.of("drop_nothing", "drop_newest", "drop_oldest");

    private final FlezhaProcessor out;
    private final TypeMirrors mirrors;

    /** The source trees, or {@code null} on a compiler that exposes none. */
    private final Trees trees;

    private final StateGenerator states;

    TaskletGenerator(FlezhaProcessor out, TypeMirrors mirrors, Trees trees, StateGenerator states) {
        this.out = out;
        this.mirrors = mirrors;
        this.trees = trees;
        this.states = states;
    }

    /** Writes the tasklet and, for an operative annotation, the short form's operative of its kind. */
    void generate(TypeElement type) {
        Declaration d = declarationOf(type);
        if (d == null || !annotationAgrees(d)) {
            return;
        }
        TaskletModel model = resolve(d);
        if (model == null) {
            return;
        }
        out.write(TypeMirrors.qualify(model.pkg(), model.taskletSimple()), renderTasklet(model), type);
        if (model.declares() != Declares.TASKLET && operativeAgrees(model, type)) {
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
        Tasklet p = type.getAnnotation(Tasklet.class);
        if (p != null) {
            found.add(new Declaration(
                    type,
                    Declares.TASKLET,
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
                            + ": a class declares one tasklet or one operative; keep one annotation");
            return null;
        }
        return found.get(0);
    }

    /**
     * The annotation's own rules: {@code @Tasklet} carries no operative member, and a §5 short
     * form writes its factory id, never derived, and declares no topology body beside its tasklet
     * body. A refusal with a contract name begins with it ({@code docs/sdk/authoring_contract.md}
     * §5.2).
     */
    private boolean annotationAgrees(Declaration d) {
        if (d.declares() == Declares.TASKLET) {
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
     * {@code @Tasklet} declares no {@code kind}, {@code sink}, {@code input} or {@code onFull}, so
     * the compiler drops a written one before the model sees it and names only a missing symbol. The
     * source tree still carries it, which lets the refusal name the annotations it belongs on.
     */
    private boolean declaresNoOperative(TypeElement type) {
        boolean agrees = true;
        for (String member : writtenMembers(type, Tasklet.class)) {
            if (OPERATIVE_MEMBERS.contains(member)) {
                out.error(
                        type,
                        "@Tasklet declares a tasklet; `" + member + "` belongs to an operative declaration:"
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
    private TaskletModel resolve(Declaration d) {
        ExecutableElement run = findRun(d.type());
        if (run == null) {
            out.error(d.type(), d.annotation() + " requires exactly one public method named `run`");
            return null;
        }
        List<? extends VariableElement> params = run.getParameters();
        // A trailing `TaskletCtx` is the multi-emit shape: the body owns every
        // emit. It sits beside the value parameters, which are counted as before.
        boolean ctxParam = !params.isEmpty()
                && mirrors.erasesTo(params.get(params.size() - 1).asType(), TASKLET_CTX);
        int valueParams = params.size() - (ctxParam ? 1 : 0);
        if (valueParams < 1 || valueParams > 2) {
            out.error(
                    run,
                    d.annotation()
                            + " `run` must be `Output run(Input)` or `Output run(Input, StateHandle<State, Change>)`,"
                            + " each optionally followed by a `TaskletCtx ctx` parameter");
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

    /** The annotation half: the cell rules, the emit shape, and the model. */
    private TaskletModel resolveDeclaration(
            Declaration d,
            ExecutableElement run,
            TypeMirror inputType,
            String pkg,
            boolean ctxParam,
            boolean stateful,
            Output output,
            StateShape state) {
        ExecutableElement cellsFor = findAnnotated(d.type(), CellsFor.class);
        ExecutableElement initState = findAnnotated(d.type(), InitState.class);
        if (!declarationsAgree(d, cellsFor, initState, stateful)) {
            return null;
        }
        String outputType = resolveEmitShape(d, run, ctxParam, output);
        if (outputType == null) {
            return null;
        }
        String simple = d.type().getSimpleName().toString();
        // A short form's written id is both its factory id and its tasklet's
        // name, verbatim, so its cells keep their state path when it grows
        // into the full form.
        String id = d.id().isEmpty() ? snake(simple) : d.id();
        return new TaskletModel(
                pkg,
                d.type().getQualifiedName().toString(),
                simple,
                inputType.toString(),
                outputType,
                state.stateType(),
                state.changeType(),
                state.handleArg(),
                initState != null
                        ? "impl." + initState.getSimpleName() + "(cellKey)"
                        : "new " + state.stateType() + "()",
                cellsFor == null ? null : cellsFor.getSimpleName().toString(),
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

    /** The state types the generated tasklet threads, or {@code null} once refused. */
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
        // handle, written beside its Change as a top-level class of the state's
        // package. It may be unresolved this round, so the state comes from the
        // generator that wrote it, and from the name for one compiled elsewhere.
        String handleName = p1.toString();
        if (!handleName.endsWith("Handle")) {
            out.error(
                    run,
                    d.annotation() + " stateful `run` second parameter must be a @State-generated `<State>Handle` "
                            + "or `StateHandle<State, Change>`");
            return null;
        }
        String handleFqn = handleName.contains(".") || pkg.isEmpty() ? handleName : pkg + "." + handleName;
        String stem = handleFqn.substring(0, handleFqn.length() - "Handle".length());
        String stateType = states.stateOf(handleFqn);
        return new StateShape(stateType != null ? stateType : stem, stem + "Change", "new " + handleFqn + "(state)");
    }

    /** The keying and static-cell declaration rules, in the order they refuse today. */
    private boolean declarationsAgree(
            Declaration d, ExecutableElement cellsFor, ExecutableElement initState, boolean stateful) {
        // Cell mode: static keying is the only mode a tasklet can declare
        // — dynamic keying moved to the wiring.
        if (findAnnotated(d.type(), CellKey.class) != null) {
            out.error(
                    d.type(),
                    "@CellKey no longer exists; declare the Cell key on the wiring instead — "
                            + "`.keyBy(f)` on the stream that feeds this tasklet");
            return false;
        }
        // Per-cell state init: @InitState maps the cell key to State.
        if (initState != null && !stateful) {
            out.error(
                    d.type(),
                    "@InitState requires a stateful run (a @State handle parameter); a stateless tasklet "
                            + "has no state to initialize");
            return false;
        }
        if (cellsFor != null && d.keys().isEmpty()) {
            out.error(
                    d.type(),
                    "static cell requires " + d.annotation() + "(keys = {...}) alongside the @CellsFor method");
            return false;
        }
        if (!d.keys().isEmpty() && cellsFor == null) {
            out.error(d.type(), d.annotation() + "(keys = {...}) requires a @CellsFor method");
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
                        d.annotation() + " `run` with a `TaskletCtx` parameter makes the body responsible for every"
                                + " emit; it must return void, and `emits = Type.class` declares the output type");
                return null;
            }
            if (!d.key().isEmpty()) {
                out.error(
                        d.type(),
                        d.annotation() + "(key = ...) conflicts with a `TaskletCtx` parameter; the body keys its own"
                                + " emits with ctx.emitKeyed(..)");
                return null;
            }
            if (d.emits() != null) {
                outputType = d.emits();
            }
        } else if (d.emits() != null) {
            out.error(
                    d.type(),
                    d.annotation() + "(emits = ...) declares the output type of a `TaskletCtx` `run`; without that"
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

    /** The {@code <Name>Tasklet} source: the interface clause, the overrides, and the {@code run} wrapper. */
    private String renderTasklet(TaskletModel m) {
        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.tasklet.Tasklet;\n");
        b.append("import com.flezha.sdk.tasklet.TaskletCtx;\n");
        b.append("import com.flezha.sdk.tasklet.TaskletException;\n");
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
                .append(m.taskletSimple())
                .append(" implements Tasklet<")
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
        // The five type tokens are not generated: the `implements Tasklet<…>`
        // clause above states them concretely, and the interface defaults read
        // them off it — the same path a hand-written tasklet takes.
        // The node name is the model's one identity, escaped as every other
        // author-written string is.
        b.append("    @Override public String name() { return ")
                .append(mirrors.literal(m.id()))
                .append("; }\n");
        b.append("    @Override public ")
                .append(m.stateType())
                .append(" initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) { return ")
                .append(m.initBody())
                .append("; }\n");
        renderStateOverrides(b, m);
        renderCellOverrides(b, m);
        renderRun(b, m);
        b.append("}\n");
        return b.toString();
    }

    /** {@code applyChange} + {@code kryoTypes}, which only a stateful tasklet carries. */
    private static void renderStateOverrides(StringBuilder b, TaskletModel m) {
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

    /** The static-cell key set and the mailbox capacity, each only when declared. */
    private void renderCellOverrides(StringBuilder b, TaskletModel m) {
        if (m.cellsForName() != null) {
            b.append("    @Override public List<String> cellKeys() { return List.of(")
                    .append(m.cellKeys().stream().map(mirrors::literal).collect(Collectors.joining(", ")))
                    .append("); }\n");
            b.append("    @Override public List<String> cellsFor(")
                    .append(m.inputType())
                    .append(" input) { return impl.")
                    .append(m.cellsForName())
                    .append("(input); }\n");
        }
        if (m.capacity() > 0) {
            b.append("    @Override public int mailboxCapacity() { return ")
                    .append(m.capacity())
                    .append("; }\n");
        }
    }

    /** The wrapper {@code run}: call the author's method, then emit what it returned. */
    private static void renderRun(StringBuilder b, TaskletModel m) {
        b.append("\n    @Override public void run(")
                .append(m.inputType())
                .append(" input, StateHandle<")
                .append(m.stateType())
                .append(", ")
                .append(m.changeType())
                .append("> state, TaskletCtx ctx) throws TaskletException {\n");
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
    private boolean operativeAgrees(TaskletModel m, TypeElement type) {
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
     * The §5 short form's operative: one entry channel into this tasklet and, with a
     * {@code sink}, this tasklet to that plugin sink, with every other operative method at its
     * interface default. The two kinds differ only in the interface they state and the method they
     * fill, because both author on the same builder.
     */
    private String renderShortFormOperative(TaskletModel m) {
        String operativeInterface = m.declares().simpleName;
        String declaration = m.declares() == Declares.WORKFLOW ? "tasklets" : "channels";

        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.channel.ChannelBuilder;\n");
        b.append("import com.flezha.sdk.operative.").append(operativeInterface).append(";\n\n");
        b.append("/** Generated single-tasklet short-form operative for ")
                .append(m.taskletSimple())
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
        b.append(".into(").append(m.taskletSimple()).append(".class)");
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

    private static ExecutableElement findRun(TypeElement tasklet) {
        ExecutableElement found = null;
        for (Element e : tasklet.getEnclosedElements()) {
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

    private static ExecutableElement findAnnotated(TypeElement tasklet, Class<? extends Annotation> ann) {
        for (Element e : tasklet.getEnclosedElements()) {
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
