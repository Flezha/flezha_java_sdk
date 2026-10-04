package com.flezha.sdk.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.runtime.FlezhaImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The start call's request and answer, over the runtime double's administration call. */
class ControlTest {

    private final Control control = new Control(FlezhaImpl.start("control_test", List::of, () -> {}));

    @AfterEach
    void reset() {
        FlezhaImpl.ADMIN_CALLS.clear();
        FlezhaImpl.ADMIN_ANSWER = "null";
    }

    @Test
    @DisplayName("a start without an id sends none and answers the generated id")
    void aStartWithoutAnIdAnswersTheGeneratedId() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"echo-k7m2qzxw4pd3fn2a\",\"outcome\":\"started\"}";

        assertThat(control.startOperator(OperatorSpec.of("echo")))
                .isEqualTo(new StartResult("echo-k7m2qzxw4pd3fn2a", StartOutcome.STARTED));
        assertThat(FlezhaImpl.ADMIN_CALLS).containsExactly(List.of("Operator.start", "[{\"factory_id\":\"echo\"}]"));
    }

    @Test
    @DisplayName("a start with an id sends it and answers already running for a live one")
    void aStartWithAnIdAnswersAlreadyRunning() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"my_echo\",\"outcome\":\"already_running\"}";

        assertThat(control.startOperator(OperatorSpec.of("echo").id("my_echo")))
                .isEqualTo(new StartResult("my_echo", StartOutcome.ALREADY_RUNNING));
        assertThat(FlezhaImpl.ADMIN_CALLS)
                .containsExactly(List.of("Operator.start", "[{\"factory_id\":\"echo\",\"id\":\"my_echo\"}]"));
    }

    @Test
    @DisplayName("a plugin is enabled by name, and bound and unbound by operator")
    void aPluginIsEnabledByNameAndBoundAndUnboundByOperator() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ArrayNode hooks = json.arrayNode();
        hooks.addArray().add("on_output").addObject();

        control.enablePlugin(
                "writer",
                PluginSpec.of("operator_file_sink").opts(json.objectNode().put("path", "out.txt")));
        control.enablePlugin(
                "feed", PluginSpec.of("operator_websocket").single().bind("my_echo", hooks));
        control.bindPlugin("writer", "my_echo");
        control.bindPlugin("writer", "my_other", json.arrayNode().add("on_output"), "in");
        control.unbindPlugin("writer", "my_echo");
        control.disablePlugin("writer");
        control.listPlugins();

        assertThat(FlezhaImpl.ADMIN_CALLS)
                .containsExactly(
                        List.of(
                                "PluginManager.enable",
                                "[\"writer\",{\"provider\":\"operator_file_sink\",\"opts\":{\"path\":\"out.txt\"}}]"),
                        List.of(
                                "PluginManager.enable",
                                "[\"feed\",{\"provider\":\"operator_websocket\",\"opts\":{},\"single\":true,"
                                        + "\"operator_id\":\"my_echo\",\"hooks\":[[\"on_output\",{}]]}]"),
                        List.of("PluginManager.bind", "[\"writer\",{\"operator_id\":\"my_echo\"}]"),
                        List.of(
                                "PluginManager.bind",
                                "[\"writer\",{\"operator_id\":\"my_other\",\"hooks\":[\"on_output\"],\"channel\":\"in\"}]"),
                        List.of("PluginManager.unbind", "[\"writer\",{\"operator_id\":\"my_echo\"}]"),
                        List.of("PluginManager.disable", "[\"writer\",{}]"),
                        List.of("PluginManager.list", "[]"));
    }

    @Test
    @DisplayName("a start answer of another shape is refused naming it")
    void aStartAnswerOfAnotherShapeIsRefused() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"my_echo\",\"outcome\":\"duplicate\"}";
        assertThatThrownBy(() -> control.startOperator(OperatorSpec.of("echo").id("my_echo")))
                .isInstanceOfSatisfying(FlezhaException.class, e -> {
                    assertThat(e.code()).isEqualTo("control.unreadable_answer");
                    assertThat(e.details()).containsEntry("verb", "Operator.start");
                });

        FlezhaImpl.ADMIN_ANSWER = "{\"outcome\":\"started\"}";
        assertThatThrownBy(() -> control.startOperator(OperatorSpec.of("echo")))
                .isInstanceOfSatisfying(
                        FlezhaException.class, e -> assertThat(e.code()).isEqualTo("control.unreadable_answer"));
    }
}
