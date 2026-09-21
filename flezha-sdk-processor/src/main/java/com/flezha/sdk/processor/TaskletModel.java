package com.flezha.sdk.processor;

import java.util.List;

/**
 * What a {@code @Tasklet}, {@code @StreamOperative} or {@code @WorkflowOperative} class resolves
 * to: the source to write, the five type arguments its {@code implements Tasklet<…>} clause
 * states, and the shape of the {@code run} wrapper. Every string is already the text the renderer
 * emits, so rendering reads no {@code javax.lang.model} type.
 */
record TaskletModel(
        String pkg,
        String userFqn,
        String userSimple,
        String inputType,
        String outputType,
        String stateType,
        String changeType,
        String handleArg,
        String initBody,
        String cellsForName,
        List<String> cellKeys,
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

    /** The generated tasklet's simple name. */
    String taskletSimple() {
        return userSimple + "Tasklet";
    }

    /** The generated short-form operative's simple name, which states the interface it implements. */
    String operativeSimple() {
        return userSimple + declares.simpleName;
    }

    /**
     * What the annotation declares: a tasklet, or a whole single-tasklet operative of a kind.
     * Each operative annotation shares its simple name with the interface its generated operative
     * implements.
     */
    enum Declares {
        TASKLET("Tasklet"),
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

    /** The state a generated tasklet threads: its two types, and what the author's {@code run} receives. */
    record StateShape(String stateType, String changeType, String handleArg) {}
}
