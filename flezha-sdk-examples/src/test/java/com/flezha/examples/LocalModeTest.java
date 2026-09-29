package com.flezha.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.examples.local.WordCountOperator;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.LocalOperator;
import com.flezha.sdk.SinkOutput;
import com.flezha.sdk.runtime.RepositoryFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * The Java SDK's local mode, driven exactly as {@link com.flezha.examples.local.Main#main} does
 * it: the user's own operator class, its declared two-tasklet graph, and no
 * controller anywhere.
 *
 * <p>The lines to feed and the output to release are read from
 * {@code test/fixtures/local_mode_word_count.txt}, which the Rust and Elixir
 * local-mode suites read too — so the Elixir SDK's own core and
 * {@code flz_core} are compared against one recorded expectation instead of by
 * inspection.
 */
class LocalModeTest {

    private static final String SCENARIO = "test/fixtures/local_mode_word_count.txt";

    @Test
    void a_declared_graph_runs_locally_and_releases_the_recorded_output() {
        List<String> lines = scenarioRows("lines: ");
        Map<String, Integer> expected = multiset(scenarioRows("totals: "));

        ConcurrentLinkedQueue<SinkOutput> released = new ConcurrentLinkedQueue<>();
        try (LocalOperator run = LocalOperator.start(WordCountOperator.class)) {
            run.onSink("totals", released::add);
            lines.forEach(line -> run.feed("lines", line));
            run.runUntilIdle(Duration.ofSeconds(20));
        }

        List<String> payloads = new ArrayList<>();
        released.forEach(out -> payloads.add(new String(out.payload(), StandardCharsets.UTF_8)));
        assertThat(multiset(payloads)).isEqualTo(expected);

        SinkOutput first = released.peek();
        assertThat(first.channel()).isEqualTo("totals");
        assertThat(first.metadata()).containsKeys("flz.cell", "flz.seq", "flz.key");
    }

    @Test
    void a_channel_the_class_does_not_declare_is_refused_by_name() {
        try (LocalOperator run = LocalOperator.start(WordCountOperator.class)) {
            assertThatThrownBy(() -> run.feed("nope", "x")).isInstanceOfSatisfying(FlezhaException.class, e -> {
                assertThat(e.code()).isEqualTo("runtime.channel_undeclared");
                assertThat(e.details()).containsEntry("channel", "nope").containsEntry("channels", List.of("lines"));
            });
            assertThatThrownBy(() -> run.onSink("nope", out -> {})).isInstanceOfSatisfying(FlezhaException.class, e -> {
                assertThat(e.code()).isEqualTo("runtime.channel_undeclared");
                assertThat(e.details()).containsEntry("channel", "nope");
            });
        }
    }

    /** The scenario's rows under one prefix, in file order. */
    private static List<String> scenarioRows(String prefix) {
        List<String> rows = new ArrayList<>();
        try {
            for (String line : RepositoryFixture.lines(SCENARIO)) {
                if (line.startsWith(prefix)) {
                    rows.add(line.substring(prefix.length()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private static Map<String, Integer> multiset(List<String> values) {
        Map<String, Integer> counted = new HashMap<>();
        values.forEach(value -> counted.merge(value, 1, Integer::sum));
        return counted;
    }
}
