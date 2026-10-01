package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.List;

/**
 * One stepped session per key inside a flow gateway, the mirror of the Rust test app's
 * {@code inner.rs}: {@code key:text} inputs; each key's session counts its turns in its own state
 * and answers {@code key#turns:text} on the gateway's {@code replies} sink. The session sleeps
 * after {@code FLEZHA_FIXTURE_SLEEP_AFTER_MS} and wakes on its key's next message with its count
 * intact, while the gateway and every other key go on. Its second stage fails on the text
 * {@code fail} (the turn is undone) and holds {@code hold} until the release env (a crash lands
 * between the two stages); the gateway's reply holds {@code stall} the same way (a crash lands
 * after the session answered).
 *
 * <p>{@code loop_gateway} embeds the chained {@code countdown}: {@code label:n} counts down one per
 * pass, and only the converged {@code label:0} reaches the gateway's {@code replies}.
 */
public final class InnerOperators {

    private InnerOperators() {}

    @Tasklet.Spec(name = "session_turn")
    public static final class SessionTurn implements Tasklet<String, CounterTasklet.State, String> {

        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(CounterTasklet.State.class, CounterChange.class);
        }

        @Override
        public void run(String input, Ctx<CounterTasklet.State, String> ctx) throws TaskletException {
            String[] parts = input.split(":", 2);
            ctx.change(new CounterChange(1));
            ctx.emit(parts[0] + "#" + ctx.state().value + ":" + (parts.length > 1 ? parts[1] : ""));
        }

        @Override
        public void compensate(
                List<String> completedOutput, Ctx<CounterTasklet.State, String> ctx) {
            ctx.change(new CounterChange(-1));
        }
    }

    @Tasklet.Spec(name = "session_check")
    public static final class SessionCheck extends NoStateStep {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            String[] parts = input.split(":", 2);
            String text = parts.length > 1 ? parts[1] : "";
            if (text.equals("fail")) {
                throw new TaskletException("session_check refuses fail");
            }
            if (text.equals("hold")) {
                StepsE2eGate.blockUntilReleased();
            }
            ctx.emit(input);
        }
    }

    /** The embedded session: registered by the gateway, never on its own. */
    public static final class Session implements Operator {

        @Override
        public String factoryId() {
            return "session";
        }

        @Override
        public Run run() {
            return Run.steps(Each.PER_INPUT);
        }

        @Override
        public Durable durable() {
            return TestEnv.fixtureDurable();
        }

        @Override
        public void channels(ChannelBuilder c) {
            // A host tasklet emits only on a channel it declares: this one is the exit.
            c.input(String.class, "turns")
                    .into(SessionTurn.class)
                    .into(SessionCheck.class)
                    .named("answer");
        }
    }

    @Tasklet.Spec(name = "gateway_reply")
    public static final class GatewayReply implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (input.endsWith(":stall")) {
                StepsE2eGate.blockUntilReleased();
            }
            ctx.emit(input);
        }
    }

    public static final class Gateway implements Operator {

        @Override
        public String factoryId() {
            return "session_gateway";
        }

        /** It journals its input (a kept instance that may sleep), so an answer is replayed. */
        @Override
        public Durable durable() {
            return new Operator.Durable.Local(Duration.ofHours(1));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests")
                    .keyBy(input -> input.split(":", 2)[0])
                    .into(Session.class, String.class)
                    .into(GatewayReply.class)
                    .sink("replies");
        }
    }

    @Tasklet.Spec(name = "countdown_step")
    public static final class CountdownStep extends NoStateStep {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            String[] parts = input.split(":", 2);
            int n = Integer.parseInt(parts[1]);
            if (n > 0) {
                ctx.emit(parts[0] + ":" + (n - 1));
            }
        }
    }

    /** The embedded countdown: registered by the loop gateway, never on its own. */
    public static final class Countdown implements Operator {

        @Override
        public String factoryId() {
            return "countdown";
        }

        @Override
        public Run run() {
            return Run.steps(Each.CHAINED);
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(CountdownStep.class).named("carry");
        }
    }

    public static final class LoopGateway implements Operator {

        @Override
        public String factoryId() {
            return "loop_gateway";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "requests")
                    .into(Countdown.class, String.class)
                    .into(GatewayReply.class)
                    .sink("replies");
        }
    }
}
