package com.flezha.sdk.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A member of the contract set, read through the directory its repository-relative paths hang off.
 *
 * <p>The set is the cross-language contracts (the declaration keys, the knob parse rule, the
 * reference topologies, the C ABI surface), and {@code test/fixtures/contract.txt} names every one.
 * The release publishes them as one archive, so an SDK developed outside the core's repository
 * vendors them at {@code contract/} rather than walking to a tree it has not got.
 *
 * <p>The directory is resolved once, in the order every SDK uses: {@code $FLEZHA_CONTRACT_DIR},
 * else the tree this module sits in, else {@code contract/} at the package root. The enclosing tree
 * comes first so a stale copy fetched into a package inside the core's checkout never shadows the
 * live files. Resolving nowhere is a failure naming the three, never a skip.
 */
public final class RepositoryFixture {

    /** The member every candidate is recognised by. */
    private static final String MANIFEST = "test/fixtures/contract.txt";

    /** Where the conformance test app sits in the core's tree, relative to any ancestor. */
    private static final String TEST_APP_IN_TREE = "test/apps/java_runner";

    private static Path ROOT;

    private RepositoryFixture() {}

    /**
     * Every line of the named repository-relative file, with comments and blanks dropped. The LINE
     * is trimmed, so a caller that splits it keeps the whitespace inside a column and loses it from
     * the first and the last one. A fixture whose outer column measures a stray space is not this
     * method's to read.
     */
    public static List<String> lines(String path) throws IOException {
        return linesOf(resolve(path));
    }

    /**
     * Every line of one file of the conformance test app, read as {@link #lines(String)} reads a
     * member. The app is not a member of the contract set: it is {@code test_app/} beside the package
     * once this SDK has a repository of its own, and {@code test/apps/java_runner} in the enclosing
     * tree while it sits inside the core's.
     */
    public static List<String> testAppLines(String file) throws IOException {
        Path beside = Path.of("..", "test_app").toAbsolutePath().normalize();
        if (Files.isRegularFile(beside.resolve(file))) {
            return linesOf(beside.resolve(file));
        }
        Path tree = carrying(Path.of("").toAbsolutePath(), TEST_APP_IN_TREE + "/" + file);
        if (tree != null) {
            return linesOf(tree.resolve(TEST_APP_IN_TREE).resolve(file));
        }
        throw new IllegalStateException("no conformance test app carries " + file + ": not "
                + beside.toAbsolutePath() + ", and no directory above "
                + Path.of("").toAbsolutePath()
                + " carries " + TEST_APP_IN_TREE);
    }

    private static List<String> linesOf(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Every row of a {@code |}-separated fixture, as its trimmed columns. A fixture where the
     * whitespace around an interior value is itself the case reads {@link #lines(String)} and
     * splits the line itself.
     */
    public static List<List<String>> rows(String path) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines(path)) {
            rows.add(Arrays.stream(line.split("\\|")).map(String::trim).toList());
        }
        return rows;
    }

    /** The last column of the one row of {@code path} that starts {@code table|name}. */
    public static String value(String path, String table, String name) throws IOException {
        return rows(path).stream()
                .filter(row -> row.get(0).equals(table) && row.get(1).equals(name))
                .map(row -> row.get(row.size() - 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " has no row " + table + "|" + name));
    }

    /** The contract set's root, resolved once for every reader in this suite. */
    private static Path contractRoot() {
        if (ROOT == null) {
            ROOT = locateContract(Path.of("").toAbsolutePath(), System.getenv("FLEZHA_CONTRACT_DIR"));
        }
        return ROOT;
    }

    /** Where the contract set is for a module directory one below its package root. */
    static Path locateContract(Path module, String configured) {
        List<Path> candidates = candidates(module, configured);
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate.resolve(MANIFEST))) {
                return candidate;
            }
        }
        List<String> unresolved =
                List.of("$FLEZHA_CONTRACT_DIR (unset)", "no directory above " + module + " carries " + MANIFEST, "");
        StringBuilder tried = new StringBuilder();
        for (int at = 0; at < candidates.size(); at++) {
            Path candidate = candidates.get(at);
            tried.append("\n  ").append(candidate == null ? unresolved.get(at) : candidate);
        }
        throw new IllegalStateException("the contract set is nowhere this suite looked:" + tried
                + "\nUnpack flezha-fixtures-<version>.tar.gz at contract/, or set FLEZHA_CONTRACT_DIR.");
    }

    private static List<Path> candidates(Path module, String configured) {
        return Arrays.asList(
                configured == null || configured.isEmpty() ? null : Path.of(configured),
                carrying(module, MANIFEST),
                module.resolveSibling("contract"));
    }

    /** The nearest ancestor of {@code from} that carries {@code marker}. */
    private static Path carrying(Path from, String marker) {
        for (Path at = from; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve(marker))) {
                return at;
            }
        }
        return null;
    }

    private static Path resolve(String path) {
        return contractRoot().resolve(path).normalize();
    }
}
