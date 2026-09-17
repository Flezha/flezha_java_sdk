package com.flezha.sdk.processor;

import com.flezha.sdk.authoring.EntitiesFor;
import com.flezha.sdk.authoring.EntityKey;
import com.flezha.sdk.authoring.InitState;
import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;
import com.flezha.sdk.processor.ProcedureModel.Output;
import com.flezha.sdk.processor.ProcedureModel.StateShape;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
 * Generates {@code <Name>Procedure} from a {@code @Procedure} class and, when it declares a
 * {@code sink}, the §5 short form's single-procedure {@code <Name>StreamOperative} or
 * {@code <Name>WorkflowOperative}. Resolution reads the {@code run} signature and the annotation
 * and reports every refusal; rendering is a pure function of the {@link ProcedureModel}.
 */
final class ProcedureGenerator {

    private static final String STATE_HANDLE = "com.flezha.sdk.state.StateHandle";
    private static final String PROCEDURE_CTX = "com.flezha.sdk.procedure.ProcedureCtx";

    private final FlezhaProcessor out;
    private final TypeMirrors mirrors;

    ProcedureGenerator(FlezhaProcessor out, TypeMirrors mirrors) {
        this.out = out;
        this.mirrors = mirrors;
    }

    /** Writes the procedure and, with a {@code sink}, the short form's operative of its kind. */
    void generate(TypeElement procedure) {
        ProcedureModel model = resolve(procedure);
        if (model == null) {
            return;
        }
        out.write(TypeMirrors.qualify(model.pkg(), model.procedureSimple()), renderProcedure(model), procedure);
        if (model.sink().isEmpty()) {
            return;
        }
        if (!model.hasOutput()) {
            out.error(
                    procedure,
                    "SinkWithoutOutput: a `sink` requires an output type to feed the plugin — a non-void"
                            + " return, or `emits = Type.class` on the context-parameter shape");
            return;
        }
        generateShortFormOperative(model, procedure);
    }

    /** The {@code run} signature half; hands the annotation half on. {@code null} once refused. */
    private ProcedureModel resolve(TypeElement procedure) {
        ExecutableElement run = findRun(procedure);
        if (run == null) {
            out.error(procedure, "@Procedure requires exactly one public method named `run`");
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
                    "@Procedure `run` must be `Output run(Input)` or `Output run(Input, StateHandle<State, Change>)`,"
                            + " each optionally followed by a `ProcedureCtx ctx` parameter");
            return null;
        }
        String pkg = mirrors.packageOf(procedure);
        Output output = resolveOutput(run);
        if (output == null) {
            return null;
        }
        StateShape state = resolveState(run, params, valueParams == 2, pkg);
        if (state == null) {
            return null;
        }
        return resolveDeclaration(
                procedure, run, params.get(0).asType(), pkg, ctxParam, valueParams == 2, output, state);
    }

    /** The {@code @Procedure(...)} half: the entity rules, the emit shape, and the model. */
    private ProcedureModel resolveDeclaration(
            TypeElement procedure,
            ExecutableElement run,
            TypeMirror inputType,
            String pkg,
            boolean ctxParam,
            boolean stateful,
            Output output,
            StateShape state) {
        ExecutableElement entitiesFor = findAnnotated(procedure, EntitiesFor.class);
        ExecutableElement initState = findAnnotated(procedure, InitState.class);
        Procedure ann = procedure.getAnnotation(Procedure.class);
        List<String> keys = List.of(ann.keys());
        if (!declarationsAgree(procedure, entitiesFor, initState, ann, stateful)) {
            return null;
        }
        String emitsType = declaredEmits(ann);
        String outputType = resolveEmitShape(procedure, run, ann.key(), ctxParam, output, emitsType);
        if (outputType == null) {
            return null;
        }
        String simple = procedure.getSimpleName().toString();
        // The procedure's node name and the operative's factory id are one
        // string: the written `id` where there is one, so Java state is saved
        // under the same name its Rust twin uses, and the derived one
        // otherwise.
        String id = ann.id().isEmpty() ? snake(simple) : ann.id();
        return new ProcedureModel(
                pkg,
                procedure.getQualifiedName().toString(),
                simple + "Procedure",
                inputType.toString(),
                outputType,
                state.stateType(),
                state.changeType(),
                state.handleArg(),
                initState != null
                        ? "impl." + initState.getSimpleName() + "(entityKey)"
                        : "new " + state.stateType() + "()",
                entitiesFor == null ? null : entitiesFor.getSimpleName().toString(),
                keys,
                ann.key(),
                id,
                ann.sink(),
                kindOf(ann),
                ann.input().isEmpty() ? "input" : ann.input(),
                ann.onFull(),
                ann.capacity(),
                stateful,
                ctxParam,
                output.emits(),
                output.optional(),
                output.emits() || emitsType != null);
    }

    /** What the return type declares, or {@code null} once refused. */
    private Output resolveOutput(ExecutableElement run) {
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
            out.error(run, "@Procedure `run` returning Optional must be parameterized as Optional<Output>");
            return null;
        }
        return new Output(true, true, element.toString());
    }

    /** The state types the generated procedure threads, or {@code null} once refused. */
    private StateShape resolveState(
            ExecutableElement run, List<? extends VariableElement> params, boolean stateful, String pkg) {
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
                    "@Procedure stateful `run` second parameter must be a @State-generated `<State>Handle` "
                            + "or `StateHandle<State, Change>`");
            return null;
        }
        String handleFqn = handleName.contains(".") || pkg.isEmpty() ? handleName : pkg + "." + handleName;
        String stateType = handleFqn.substring(0, handleFqn.length() - "Handle".length());
        return new StateShape(stateType, stateType + "Change", "new " + handleFqn + "(state)");
    }

    /** The keying and static-entity declaration rules, in the order they refuse today. */
    private boolean declarationsAgree(
            TypeElement procedure,
            ExecutableElement entitiesFor,
            ExecutableElement initState,
            Procedure ann,
            boolean stateful) {
        List<String> keys = List.of(ann.keys());
        // Entity mode: static keying is the only mode a procedure can declare
        // — dynamic keying moved to the wiring.
        if (findAnnotated(procedure, EntityKey.class) != null) {
            out.error(
                    procedure,
                    "@EntityKey no longer exists; declare the Entity key on the wiring instead — "
                            + "`.keyBy(f)` on the stream that feeds this procedure");
            return false;
        }
        // Per-entity state init: @InitState maps the procedure key to State.
        if (initState != null && !stateful) {
            out.error(
                    procedure,
                    "@InitState requires a stateful run (a @State handle parameter); a stateless procedure "
                            + "has no state to initialize");
            return false;
        }
        if (entitiesFor != null && keys.isEmpty()) {
            out.error(procedure, "static entity requires @Procedure(keys = {...}) alongside the @EntitiesFor method");
            return false;
        }
        if (!keys.isEmpty() && entitiesFor == null) {
            out.error(procedure, "@Procedure(keys = {...}) requires a @EntitiesFor method");
            return false;
        }
        return shortFormAgrees(procedure, ann);
    }

    /**
     * The §5 short form's own rules: a {@code sink} makes one procedure a whole operative, so it
     * needs an identity and a kind, and the keys that describe that operative need a {@code sink}.
     * Each message begins with the refusal's contract name
     * ({@code docs/sdk/authoring_contract.md} §5.2).
     */
    private boolean shortFormAgrees(TypeElement procedure, Procedure ann) {
        if (ann.sink().isEmpty()) {
            List<String> keys = new ArrayList<>();
            if (!ann.input().isEmpty()) {
                keys.add("input");
            }
            if (ann.kind().length > 0) {
                keys.add("kind");
            }
            if (keys.isEmpty()) {
                return true;
            }
            out.error(
                    procedure,
                    "ShorthandKeyWithoutSink: `" + String.join("`, `", keys)
                            + "` name(s) of the operative a `sink` generates; this class declares no `sink`,"
                            + " so there is nothing for them to describe");
            return false;
        }
        if (ann.id().isEmpty()) {
            out.error(
                    procedure,
                    "SinkWithoutId: a `sink` makes this class a whole operative, whose factory id is an"
                            + " address an operator types; write it as `id = \"...\"` rather than deriving it"
                            + " from the class name");
            return false;
        }
        if (ann.kind().length == 0) {
            out.error(
                    procedure,
                    "SinkWithoutKind: a `sink` makes this class a whole operative; write"
                            + " `kind = OperativeKind.STREAM` or `kind = OperativeKind.WORKFLOW`");
            return false;
        }
        if (ann.kind().length > 1) {
            out.error(procedure, "@Procedure(kind = ...) declares one kind, not " + ann.kind().length);
            return false;
        }
        return true;
    }

    /** The declared kind, or {@code STREAM} for a class with no {@code sink} (which has no operative). */
    private static OperativeKind kindOf(Procedure ann) {
        return ann.kind().length == 1 ? ann.kind()[0] : OperativeKind.STREAM;
    }

    /** Reconciles {@code emits} and {@code key} with the run shape; the output type, or {@code null} once refused. */
    private String resolveEmitShape(
            TypeElement procedure,
            ExecutableElement run,
            String emitKey,
            boolean ctxParam,
            Output output,
            String emitsType) {
        String outputType = output.type();
        if (ctxParam) {
            if (output.emits()) {
                out.error(
                        run,
                        "@Procedure `run` with a `ProcedureCtx` parameter makes the body responsible for every"
                                + " emit; it must return void, and `emits = Type.class` declares the output type");
                return null;
            }
            if (!emitKey.isEmpty()) {
                out.error(
                        procedure,
                        "@Procedure(key = ...) conflicts with a `ProcedureCtx` parameter; the body keys its own"
                                + " emits with ctx.emitKeyed(..)");
                return null;
            }
            if (emitsType != null) {
                outputType = emitsType;
            }
        } else if (emitsType != null) {
            out.error(
                    procedure,
                    "@Procedure(emits = ...) declares the output type of a `ProcedureCtx` `run`; without that"
                            + " parameter the return type declares it");
            return null;
        }
        if (!emitKey.isEmpty() && !output.emits()) {
            out.error(procedure, "@Procedure(key = ...) requires a non-void return type; there is no emit to key");
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
        b.append("/** Generated by FlezhaProcessor from @Procedure on ")
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
     * The §5 short form's operative: one entry channel into this procedure and this procedure to
     * the named plugin sink, with every other operative method at its interface default. The two
     * kinds differ only in the interface they state and the method they fill, because both author
     * on the same builder.
     */
    private void generateShortFormOperative(ProcedureModel m, Element origin) {
        boolean workflow = m.kind() == OperativeKind.WORKFLOW;
        String operativeInterface = workflow ? "WorkflowOperative" : "StreamOperative";
        String declaration = workflow ? "procedures" : "channels";
        String operativeSimple = m.procedureSimple().replaceFirst("Procedure$", "") + operativeInterface;
        String operativeFqn = TypeMirrors.qualify(m.pkg(), operativeSimple);
        String onFullCall = onFullCall(m.onFull(), origin);

        StringBuilder b = FlezhaProcessor.source(m.pkg());
        b.append("import com.flezha.sdk.channel.ChannelBuilder;\n");
        b.append("import com.flezha.sdk.operative.").append(operativeInterface).append(";\n\n");
        b.append("/** Generated single-procedure short-form operative for ")
                .append(m.procedureSimple())
                .append(". */\n");
        b.append("public final class ")
                .append(operativeSimple)
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
                .append(")")
                .append(onFullCall)
                .append(".into(")
                .append(m.procedureSimple())
                .append(".class).sink(")
                .append(mirrors.literal(m.sink()))
                .append(");\n");
        b.append("    }\n}\n");

        out.write(operativeFqn, b.toString(), origin);
    }

    /**
     * The {@code emits = Type.class} output declaration, or {@code null} when
     * unset. Reading a {@code Class} member during annotation processing throws
     * {@link MirroredTypeException} — the mirror it carries is the answer.
     */
    private String declaredEmits(Procedure ann) {
        TypeMirror mirror;
        try {
            Class<?> declared = ann.emits();
            return declared == void.class ? null : declared.getCanonicalName();
        } catch (MirroredTypeException e) {
            mirror = e.getTypeMirror();
        }
        return mirror.getKind() == TypeKind.VOID ? null : mirror.toString();
    }

    private String onFullCall(String onFull, Element origin) {
        if (onFull == null || onFull.isEmpty()) {
            return "";
        }
        String variant =
                switch (onFull) {
                    case "drop_nothing" -> "DROP_NOTHING";
                    case "drop_newest" -> "DROP_NEWEST";
                    case "drop_oldest" -> "DROP_OLDEST";
                    default -> null;
                };
        if (variant == null) {
            out.error(origin, "@Procedure onFull must be one of: drop_nothing, drop_newest, drop_oldest");
            return "";
        }
        return ".onFull(com.flezha.sdk.channel.OverflowPolicy." + variant + ")";
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
