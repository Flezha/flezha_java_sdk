package com.flezha.sdk.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.FlezhaImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The start call's request and answer, over the runtime double's administration call. */
class AdminTest {

    private final Admin admin = new Admin(FlezhaImpl.start("admin_test", List::of, () -> {}));

    @AfterEach
    void reset() {
        FlezhaImpl.ADMIN_CALLS.clear();
        FlezhaImpl.ADMIN_ANSWER = "null";
    }

    @Test
    @DisplayName("a start without an id sends none and answers the generated id")
    void aStartWithoutAnIdAnswersTheGeneratedId() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"echo-k7m2qzxw4pd3fn2a\",\"outcome\":\"started\"}";

        assertThat(admin.startOperator(OperatorSpec.of("echo")))
                .isEqualTo(new StartResult("echo-k7m2qzxw4pd3fn2a", StartOutcome.STARTED));
        assertThat(FlezhaImpl.ADMIN_CALLS).containsExactly(List.of("Operator.start", "[{\"factory_id\":\"echo\"}]"));
    }

    @Test
    @DisplayName("a start with an id sends it and answers already running for a live one")
    void aStartWithAnIdAnswersAlreadyRunning() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"my_echo\",\"outcome\":\"already_running\"}";

        assertThat(admin.startOperator(OperatorSpec.of("echo").id("my_echo")))
                .isEqualTo(new StartResult("my_echo", StartOutcome.ALREADY_RUNNING));
        assertThat(FlezhaImpl.ADMIN_CALLS)
                .containsExactly(List.of("Operator.start", "[{\"factory_id\":\"echo\",\"id\":\"my_echo\"}]"));
    }

    @Test
    @DisplayName("a start answer of another shape is refused naming it")
    void aStartAnswerOfAnotherShapeIsRefused() {
        FlezhaImpl.ADMIN_ANSWER = "{\"id\":\"my_echo\",\"outcome\":\"duplicate\"}";
        assertThatThrownBy(() -> admin.startOperator(OperatorSpec.of("echo").id("my_echo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");

        FlezhaImpl.ADMIN_ANSWER = "{\"outcome\":\"started\"}";
        assertThatThrownBy(() -> admin.startOperator(OperatorSpec.of("echo")))
                .isInstanceOf(IllegalStateException.class);
    }
}
