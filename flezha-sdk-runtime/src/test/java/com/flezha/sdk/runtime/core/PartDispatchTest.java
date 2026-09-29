package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ctx.part}: what the host hands the core's part entry. The numbering, the stamps and the
 * drops are the core's, so what is asserted here is the host's half: the activation's token, the
 * item's index in the batch, the channel's encoding, and that the part leaves while {@code run} is
 * still running.
 */
class PartDispatchTest extends DispatchTestBase {

    /** Arms the recording entry to refuse, as the core refuses a channel with no sink. */
    static final AtomicBoolean REFUSING = new AtomicBoolean();

    @BeforeEach
    void recordParts() {
        REFUSING.set(false);
        dispatcher = new ActivationDispatcher((token, item, payload) -> {
            if (REFUSING.get()) {
                throw FlezhaException.of("part.no_sink", Map.of("tasklet", "part_task", "channel", "answer"));
            }
            RECORDED.add("part " + token + "/" + item + "/" + new String(payload, StandardCharsets.UTF_8));
        });
    }

    @Test
    @DisplayName(
            "each item's parts carry the activation's token, the item's index and the sink's encoding, before run returns")
    void partsCarryTheTokenAndTheItemIndex() {
        UnboundedOperatorClassRegistry.registerClass(PartOperator.class);
        ActivationOutcome out = outcome(work("part_op", 0, Optional.empty(), 0, input(0, "2", 1), input(0, "1", 2)));

        assertThat(RECORDED)
                .containsExactly("part 1/0/<1>", "part 1/0/<2>", "returned 2", "part 1/1/<1>", "returned 1");
        List<ItemEffect> effects = ((ActivationOutcome.Work) out).effects();
        assertThat(effects).hasSize(2);
        assertThat(effects.get(0).outputs())
                .as("a part is not an output")
                .singleElement()
                .satisfies(o -> assertThat(new String(o.payload(), StandardCharsets.UTF_8))
                        .isEqualTo("<done:2>"));
    }

    @Test
    @DisplayName("a timer handler sends a part under its own item")
    void aTimerHandlerSendsAPart() {
        UnboundedOperatorClassRegistry.registerClass(PartOperator.class);
        soleEffect(work("part_op", 0, Optional.empty(), 0, new WorkItem.Timer(TimerKind.PROCESSING_TIME, 5)));

        assertThat(RECORDED).containsExactly("part 1/0/<fired 5>");
    }

    @Test
    @DisplayName("a per_input bounded stage sends its parts as an unbounded tasklet does")
    void aBoundedStageSendsParts() {
        BoundedOperatorClassRegistry.registerClass(PartBounded.class);
        ItemEffect effect = soleEffect(work("part_bounded", 0, Optional.empty(), 0, input(0, "2", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.Ok.class);
        assertThat(RECORDED).containsExactly("part 1/0/<1>", "part 1/0/<2>", "returned 2");
    }

    @Test
    @DisplayName("a refused part fails the invocation as any raise does")
    void aRefusedPartFailsTheInvocation() {
        UnboundedOperatorClassRegistry.registerClass(PartOperator.class);
        REFUSING.set(true);
        ItemEffect effect = soleEffect(work("part_op", 0, Optional.empty(), 0, input(0, "1", 1)));

        assertThat(effect.verdict()).isInstanceOf(Verdict.RetryableError.class);
        assertThat(((Verdict.RetryableError) effect.verdict()).message()).contains("part.no_sink");
        assertThat(effect.outputs()).isEmpty();
        assertThat(RECORDED).isEmpty();
    }

    public static final class PartOperator extends Op {
        public PartOperator() {
            super(
                    "part_op",
                    c -> c.input(String.class, "in").into(PartTask.class).sink("answer", PartDispatchTest::bracketed));
        }
    }

    /** {@code PER_INPUT}, as a session turn is. */
    public static final class PartBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "part_bounded";
        }

        @Override
        public LoopMode loopMode() {
            return LoopMode.PER_INPUT;
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(PartTask.class).sink("answer", PartDispatchTest::bracketed);
        }
    }

    /** Sends the parts {@code 1..n} for the input {@code n}, then emits {@code done:<n>}. */
    @Tasklet.Spec(name = "part_task")
    public static final class PartTask implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            for (int part = 1; part <= Integer.parseInt(input); part++) {
                ctx.part(Integer.toString(part));
            }
            RECORDED.add("returned " + input);
            ctx.emit("done:" + input);
        }

        @Override
        public void onTimer(long firedAt, TimerKind kind, Ctx<Void, String> ctx) {
            ctx.part("fired " + firedAt);
        }
    }

    /** A sink encoding a part could not be mistaken for the default one. */
    private static byte[] bracketed(String value) {
        return ("<" + value + ">").getBytes(StandardCharsets.UTF_8);
    }
}
