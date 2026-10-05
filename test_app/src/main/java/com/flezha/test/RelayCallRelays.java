package com.flezha.test;

import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.Optional;

/**
 * The {@code relay_call} fixtures, the Java mirror of the Rust test app's: relays that call
 * another relay from their graph and read the outcome as a {@link Response}. Every outcome is
 * written as one line, {@code <request>=ok:<answer>@<target>/<created>} or
 * {@code <request>=error:<code>@<target>/<created>}.
 */
public final class RelayCallRelays {

    /** The deadline every fixture call carries. */
    static final long DEADLINE_MS = 30_000;

    /** The prefix an outcome line carries, which no request does. */
    private static final String OUTCOME = "response ";

    /** The prefix of a turn that may only reach a session already running. */
    private static final String RESUME = "resume:";

    private RelayCallRelays() {}

    /** One outcome as the line every SDK's fixture writes. */
    static String render(Response<String, String> response) {
        String outcome = response.ok() ? "ok:" + response.answer() : "error:" + response.error().code();
        return response.request() + "=" + outcome + "@" + response.target() + "/" + response.created();
    }

    /** The session a turn or an outcome line belongs to: what precedes its {@code :}. */
    static String session(String line) {
        String turn = line.startsWith(OUTCOME) ? line.substring(OUTCOME.length()) : line;
        int colon = turn.indexOf(':');
        return colon < 0 ? turn : turn.substring(0, colon);
    }

    /** What reaches a calling tasklet: a turn from outside, or the outcome of one it sent. */
    public record Turn(String turn, Response<String, String> outcome) {
        static Turn of(String turn) {
            return new Turn(turn, null);
        }

        static Turn of(Response<String, String> outcome) {
            return new Turn(null, outcome);
        }
    }

    public static final class CallPricing implements Relay {
        @Override
        public String factoryId() {
            return "call_pricing";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in").into(PricingQuote.class).response();
        }
    }

    public static final class CallOrders implements Relay {
        @Override
        public String factoryId() {
            return "call_orders";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .into(OrderAsk.class)
                    .named("order")
                    .call(CallTarget.id("pricing"), String.class, DEADLINE_MS)
                    .into(OrderPriced.class)
                    .sink("priced");
        }
    }

    /** A durable keep-progress session answering {@code answer:<turn>}. */
    public static final class CallAgent implements Relay {
        @Override
        public String factoryId() {
            return "call_agent";
        }

        @Override
        public Run run() {
            return new Run.Steps(Each.PER_INPUT, OnFailure.KEEP_PROGRESS);
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
            c.input(String.class, "turn").into(AgentTurn.class).response();
        }
    }

    /**
     * Calls {@code call_agent} by the session key of each {@code <session>:<text>} turn; a
     * {@code resume:<session>:<text>} turn reaches only a session already running.
     */
    public static final class CallGateway implements Relay {
        @Override
        public String factoryId() {
            return "call_gateway";
        }

        @Override
        public Run run() {
            return new Run.Flow(new Exactly.EffectivelyOnce());
        }

        @Override
        public void channels(ChannelBuilder c) {
            Stream<String> account = c.input(String.class, "in")
                    .map(Turn.class, Turn::of)
                    .into(GatewayAccount.class)
                    .named("gateway");
            account.filter(line -> !line.startsWith(OUTCOME) && !line.startsWith(RESUME))
                    .keyBy(RelayCallRelays::session)
                    .call(CallTarget.factory("call_agent"), String.class, DEADLINE_MS)
                    .map(Turn.class, Turn::of)
                    .into(GatewayAccount.class);
            account.filter(line -> line.startsWith(RESUME))
                    .into(GatewayResume.class)
                    .named("resume")
                    .keyBy(RelayCallRelays::session)
                    .call(CallTarget.factory("call_agent").create(false), String.class, DEADLINE_MS)
                    .map(Turn.class, Turn::of)
                    .into(GatewayAccount.class);
            account.filter(line -> line.startsWith(OUTCOME))
                    .keyBy(RelayCallRelays::session)
                    .into(GatewayLog.class)
                    .keyed()
                    .sink("gateway_out");
        }
    }

    /** A durable session calling {@code pricing} from a turn and reading the outcome as its next. */
    public static final class CallToolSession implements Relay {
        @Override
        public String factoryId() {
            return "call_tool_session";
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
            Stream<String> turn = c.input(String.class, "turn")
                    .map(Turn.class, Turn::of)
                    .into(ToolTurn.class)
                    .named("tool");
            turn.filter(line -> !line.startsWith(OUTCOME))
                    .call(CallTarget.id("pricing"), String.class, DEADLINE_MS)
                    .map(Turn.class, Turn::of)
                    .into(ToolTurn.class);
            turn.filter(line -> line.startsWith(OUTCOME)).into(ToolLog.class).sink("tool_out");
        }
    }

    @Tasklet.Spec(name = "pricing_quote")
    public static final class PricingQuote extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (!input.startsWith("silent")) {
                ctx.emit("price:" + input);
            }
        }
    }

    @Tasklet.Spec(name = "order_ask")
    public static final class OrderAsk extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "order_priced")
    public static final class OrderPriced implements Tasklet<Response<String, String>, Void, String> {
        @Override
        public void run(Response<String, String> input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(render(input));
        }
    }

    @Tasklet.Spec(name = "agent_turn")
    public static final class AgentTurn extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            int colon = input.indexOf(':');
            String text = colon < 0 ? input : input.substring(colon + 1);
            if (text.equals("boom")) {
                throw new TaskletException.NonRetryable("the turn failed");
            }
            if (text.equals("slow")) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ctx.emit("answer:" + input);
        }
    }

    /** A caller's tasklet: a turn goes on as it came, an outcome as its line. */
    abstract static class Account implements Tasklet<Turn, Void, String> {
        @Override
        public void run(Turn input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.outcome() == null ? input.turn() : OUTCOME + render(input.outcome()));
        }
    }

    /** A resume turn without its prefix. */
    @Tasklet.Spec(name = "gateway_resume")
    public static final class GatewayResume extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.substring(RESUME.length()));
        }
    }

    @Tasklet.Spec(name = "gateway_account")
    public static final class GatewayAccount extends Account {}

    @Tasklet.Spec(name = "tool_turn")
    public static final class ToolTurn extends Account {}

    /** An outcome line without its prefix. */
    abstract static class OutcomeLog extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input.substring(OUTCOME.length()));
        }
    }

    @Tasklet.Spec(name = "gateway_log")
    public static final class GatewayLog extends OutcomeLog {}

    @Tasklet.Spec(name = "tool_log")
    public static final class ToolLog extends OutcomeLog {}
}
