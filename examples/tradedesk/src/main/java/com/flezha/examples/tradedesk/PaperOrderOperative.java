package com.flezha.examples.tradedesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operative.WorkflowOperative;
import javax.annotation.Nonnull;

/**
 * The desk's workflow: one instance per order, which runs its steps once and then ends.
 *
 * <p>A workflow declares its steps the way a stream operative declares its graph, on the same
 * builder, and the controller schedules, restarts and relocates it in the same way. What differs
 * is the lifecycle: each step's output is written down as it commits, so an instance that is
 * interrupted resumes at the step it reached rather than from the beginning, and the instance ends
 * by itself once the chain has run.
 *
 * <p>When a step fails and its retries are spent, the instance ends abnormally instead, and the
 * default rollback undoes every step that finished, in reverse, through their {@code compensate}
 * methods: the order is cancelled, then the money is released.
 */
public final class PaperOrderOperative implements WorkflowOperative {

    /**
     * The order an instance is started with. The entry channel declares this type, so the start
     * values are decoded into it: {@code --init-values '{"symbol": "AAPL", "quantity": 10}'}.
     */
    public record OrderTicket(String symbol, int quantity) {}

    /** What the two steps before {@link PlaceOrder} deliver, and which of them sent it. */
    public record Step(String source, OrderTicket order, String note) {}

    /**
     * The operative's stable identity, and the name {@code flz operative start paper_order
     * --kind workflow} resolves. It is durable, so changing it declares a new operative rather
     * than renaming this one.
     */
    @Override
    @Nonnull
    public String factoryId() {
        return "paper_order";
    }

    /**
     * Invoked once at registration to declare the chain.
     *
     * <p>A workflow has a single root, so its entry channel feeds one step. What the first step
     * hands on is read twice: the risk desk looks at it while it also waits at the merge, and the
     * step that places the order is dispatched only once both have committed. The handoffs between
     * steps are named and nothing else, so only the fill reaches the plugin layer, on
     * {@code orders}, and {@code sealed} states that nothing follows it.
     */
    @Override
    public void procedures(@Nonnull ChannelBuilder c) {
        EntryChannel<OrderTicket> order = c.input(OrderTicket.class, "order");

        Stream<ReserveFunds.Reserved> reserved = order.into(ReserveFunds.class).named("reserved");
        Stream<CheckRisk.Verdict> risk = reserved.into(CheckRisk.class).named("risk");

        c.merge(Step.class)
                .from(reserved, r -> new Step("funds", r.order(), r.reservationId()))
                .from(risk, v -> new Step("risk", v.order(), v.verdict()))
                .into(PlaceOrder.class)
                .named("placed")
                .into(ConfirmFill.class)
                .sink("orders")
                .sealed();
    }
}
