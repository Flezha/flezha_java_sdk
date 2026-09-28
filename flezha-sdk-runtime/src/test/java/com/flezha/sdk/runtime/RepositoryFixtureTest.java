package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryFixtureTest {

    private static final String MANIFEST = "test/fixtures/contract.txt";

    @TempDir
    Path checkout;

    @Test
    @DisplayName("An in-tree contract/ copy does not shadow the checkout the package sits in")
    void anInTreeCopyDoesNotShadowTheCheckout() throws IOException {
        Path module = checkout.resolve("sdk/java/flezha-sdk-runtime");
        carryManifest(checkout);
        carryManifest(module.resolveSibling("contract"));

        assertThat(RepositoryFixture.locateContract(module, null)).isEqualTo(checkout);

        Files.delete(checkout.resolve(MANIFEST));
        assertThat(RepositoryFixture.locateContract(module, null)).isEqualTo(module.resolveSibling("contract"));
    }

    private static void carryManifest(Path tree) throws IOException {
        Files.createDirectories(tree.resolve("test/fixtures"));
        Files.writeString(tree.resolve(MANIFEST), "");
    }
}
