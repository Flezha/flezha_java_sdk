package com.flezha.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.runtime.RepositoryFixture;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every public concept spelled as {@code test/fixtures/sdk_public_names.txt} spells it.
 *
 * <p>A Java cell is a binary class name, optionally followed by {@code #member} for a public method
 * of it, or {@code package:name} for a package the authoring artifact exports to everyone. A cell
 * starting with {@code !} names something that must not be public. It runs here, where the api,
 * the runtime and the harness are all on the class path.
 */
class PublicNamesTest {

    /** {@code concept|python|ruby|php|node|elixir|java|rust}. */
    private static final int COLUMN = 6;

    private static final String API_MODULE = "com.flezha.sdk.api";

    @Test
    @DisplayName("every concept has its one public spelling")
    void everyConceptHasItsOnePublicSpelling() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String line : RepositoryFixture.lines("test/fixtures/sdk_public_names.txt")) {
            String[] row = line.split("\\|", -1);
            for (String path : row[COLUMN].trim().split("\\s+")) {
                String name = path.startsWith("!") ? path.substring(1) : path;
                boolean hidden = !name.equals(path);
                if (!path.equals("-") && resolves(name) == hidden) {
                    wrong.add(row[0] + ": `" + name + "` is " + (hidden ? "public" : "missing"));
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    private static boolean resolves(String path) throws IOException {
        if (path.startsWith("package:")) {
            return exportedToEveryone(path.substring("package:".length()));
        }
        String[] parts = path.split("#", 2);
        Class<?> type;
        try {
            type = Class.forName(parts[0], false, PublicNamesTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return false;
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        return parts.length == 1
                || Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals(parts[1]));
    }

    /** Whether the api's module descriptor exports {@code pkg} without naming who may read it. */
    private static boolean exportedToEveryone(String pkg) throws IOException {
        ModuleDescriptor api = null;
        for (URL url : Collections.list(PublicNamesTest.class.getClassLoader().getResources("module-info.class"))) {
            try (InputStream in = url.openStream()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(in);
                api = descriptor.name().equals(API_MODULE) ? descriptor : api;
            }
        }
        assertThat(api).as("the api's module descriptor").isNotNull();
        return api.exports().stream().anyMatch(e -> e.source().equals(pkg) && !e.isQualified());
    }
}
