package com.flezha.sdk.processor;

import java.util.List;

/**
 * What a {@code @Procedure}, {@code @StreamOperative} or {@code @WorkflowOperative} class resolves
 * to: the source to write, the five type arguments its {@code implements Procedure<…>} clause
 * states, and the shape of the {@code run} wrapper. Every string is already the text the renderer
 * emits, so rendering reads no {@code javax.lang.model} type.
 */
record ProcedureModel(
        String pkg,
        String userFqn,
        String userSimple,
        String inputType,
        String outputType,
        String stateType,
        String changeType,
        String handleArg,
        String initBody,
        String entitiesForName,
        List<String> entityKeys,
        String emitKey,
        String id,
        Declares declares,
        String sink,
        String input,
        String onFull,
        int capacity,
        boolean stateful,
        boolean ctxParam,
        boolean emits,
        boolean optional,
        boolean hasOutput) {

    /** The generated procedure's simple name. */
    String procedureSimple() {
        return userSimple + "Procedure";
    }

    /** The generated short-form operative's simple name, which states the interface it implements. */
    String operativeSimple() {
        return userSimple + declares.simpleName;
    }

    /**
     * What the annotation declares: a procedure, or a whole single-procedure operative of a kind.
     * Each operative annotation shares its simple name with the interface its generated operative
     * implements.
     */
    enum Declares {
        PROCEDURE("Procedure"),
        STREAM("StreamOperative"),
        WORKFLOW("WorkflowOperative");

        final String simpleName;

        Declares(String simpleName) {
            this.simpleName = simpleName;
        }

        /** The annotation as the author writes it, which every refusal of a declaration names. */
        String annotation() {
            return "@" + simpleName;
        }
    }

    /** What the {@code run} return type declares: whether it emits, and of what. */
    record Output(boolean emits, boolean optional, String type) {}

    /** The state a generated procedure threads: its two types, and what the author's {@code run} receives. */
    record StateShape(String stateType, String changeType, String handleArg) {}
}
