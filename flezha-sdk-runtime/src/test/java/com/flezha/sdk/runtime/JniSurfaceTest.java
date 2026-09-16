package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.native_.FlzCore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@code FlzCore}'s native methods against {@code test/fixtures/jni_surface.txt}.
 *
 * <p>The core's own test holds its JNI exports to the same file, so a method declared here and
 * exported nowhere fails in this repository rather than at its first call. Reflection reads the
 * class without initialising it, so no native library has to be staged.
 */
class JniSurfaceTest {

    @Test
    void flzCoreDeclaresExactlyTheNativeMethodsTheFixtureLists() throws Exception {
        TreeSet<String> listed = new TreeSet<>();
        for (List<String> row : RepositoryFixture.rows("test/fixtures/jni_surface.txt")) {
            listed.add(row.get(0));
        }
        TreeSet<String> declared = new TreeSet<>();
        for (Method method : FlzCore.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isNative(modifiers) && Modifier.isStatic(modifiers)) {
                declared.add(method.getName());
            }
        }

        List<String> unlisted = new ArrayList<>(declared);
        unlisted.removeAll(listed);
        List<String> undeclared = new ArrayList<>(listed);
        undeclared.removeAll(declared);

        assertThat(unlisted)
                .as("FlzCore declares native methods jni_surface.txt does not list")
                .isEmpty();
        assertThat(undeclared)
                .as("jni_surface.txt lists native methods FlzCore does not declare")
                .isEmpty();
    }
}
