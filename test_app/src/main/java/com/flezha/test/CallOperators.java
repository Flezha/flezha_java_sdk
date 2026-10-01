package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.Optional;

/**
 * The {@code call_response} fixtures, the Java mirror of the Rust test app's: operators an outside
 * caller calls and whose graph answers it through {@code response()} — from the first tasklet, from
 * a downstream one, from whichever of two filtered paths the input took, and from a durable steps
 * turn.
 */
public final class CallOperators {

    private CallOperators() {}

    public static final class CallEcho implements Operator {
        @Override
        public String factoryId() {
            return "call_echo";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Echo.class).response();
        }
    }

    public static final class CallDownstream implements Operator {
        @Override
        public String factoryId() {
            return "call_downstream";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Parse.class).into(Answer.class).response();
        }
    }

    /** An input starting {@code no} takes the refused path, every other the accepted one. */
    public static final class CallSplit implements Operator {
        @Override
        public String factoryId() {
            return "call_split";
        }

        @Override
        public void channels(ChannelBuilder c) {
            var admitted = c.input(String.class, "in").into(Admit.class);
            admitted.filter(v -> !v.startsWith("no")).into(Accept.class).response();
            admitted.filter(v -> v.startsWith("no")).into(Refuse.class).response();
        }
    }

    public static final class CallTurn implements Operator {
        @Override
        public String factoryId() {
            return "call_turn";
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
        public Optional<Duration> maxDuration() {
            return Optional.of(Duration.ofSeconds(600));
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(Turn.class).response();
        }
    }

    @Tasklet.Spec(name = "call_echo")
    public static final class Echo extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "call_parse")
    public static final class Parse extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "call_answer")
    public static final class Answer extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("re:" + input);
        }
    }

    @Tasklet.Spec(name = "call_admit")
    public static final class Admit extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "call_accept")
    public static final class Accept extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("accepted:" + input);
        }
    }

    @Tasklet.Spec(name = "call_refuse")
    public static final class Refuse extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("refused:" + input);
        }
    }

    @Tasklet.Spec(name = "call_turn")
    public static final class Turn extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }
}
