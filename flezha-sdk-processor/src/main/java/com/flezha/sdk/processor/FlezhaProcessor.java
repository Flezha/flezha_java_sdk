package com.flezha.sdk.processor;

import com.flezha.sdk.authoring.State;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Generates {@code Tasklet} and short-form operative implementations from the
 * {@code @Tasklet} / {@code @StreamOperative} / {@code @WorkflowOperative} / {@code @State}
 * authoring annotations, mirroring the Rust SDK macros. See {@code docs/sdk/authoring_contract.md}.
 *
 * <p>Per round: {@code @State} classes generate a {@code <Name>Change}
 * (field-granular variants + {@code apply} + {@code kryoTypes}); {@code @Tasklet}
 * classes generate a {@code <Name>Tasklet}, and {@code @StreamOperative} /
 * {@code @WorkflowOperative} classes generate it beside the §5 short form's
 * {@code <Name>StreamOperative} or {@code <Name>WorkflowOperative}.
 *
 * <p>Nothing is collected and nothing registers itself: the application hands each short-form
 * class to {@code FlezhaRunner.registerFactory}, as it hands it any operative.
 *
 * <p>This class owns the rounds and the two sinks every generator writes through
 * ({@link #write} and {@link #error}); {@link StateGenerator} and
 * {@link TaskletGenerator} own what is generated.
 */
@SupportedAnnotationTypes({
    "com.flezha.sdk.authoring.Tasklet",
    "com.flezha.sdk.authoring.StreamOperative",
    "com.flezha.sdk.authoring.WorkflowOperative",
    "com.flezha.sdk.authoring.State"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class FlezhaProcessor extends AbstractProcessor {

    private Filer filer;
    private StateGenerator states;
    private TaskletGenerator tasklets;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.filer = env.getFiler();
        TypeMirrors mirrors = new TypeMirrors(env.getElementUtils(), env.getTypeUtils());
        this.states = new StateGenerator(this, mirrors);
        this.tasklets = new TaskletGenerator(this, mirrors, sourceTrees(env), states);
    }

    /** The compiler's source trees, which only javac exposes; {@code null} elsewhere. */
    private static Trees sourceTrees(ProcessingEnvironment env) {
        try {
            return Trees.instance(env);
        } catch (IllegalArgumentException notJavac) {
            return null;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element e : roundEnv.getElementsAnnotatedWith(State.class)) {
            states.generate((TypeElement) e);
        }
        for (Element e : roundEnv.getElementsAnnotatedWithAny(TaskletGenerator.DECLARATIONS)) {
            tasklets.generate((TypeElement) e);
        }
        return false;
    }

    /** Opens a generated source with its package declaration; the default package gets none. */
    static StringBuilder source(String pkg) {
        StringBuilder b = new StringBuilder();
        if (!pkg.isEmpty()) {
            b.append("package ").append(pkg).append(";\n\n");
        }
        return b;
    }

    void write(String fqn, String content, Element... origin) {
        try {
            var file = filer.createSourceFile(fqn, origin);
            try (Writer w = file.openWriter()) {
                w.write(content);
            }
        } catch (IOException ex) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "FlezhaProcessor: failed to write " + fqn + ": " + ex);
        }
    }

    void error(Element e, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, e);
    }
}
