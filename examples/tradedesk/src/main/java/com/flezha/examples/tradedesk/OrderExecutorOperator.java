package com.flezha.examples.tradedesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import javax.annotation.Nonnull;

/**
 * The desk's bounded operator: one instance per order, which runs its steps once and then ends.
 *
 * <p>A bounded operator declares its steps the way an unbounded operator declares its graph, on the same
 * builder, and the controller schedules, restarts and relocates it in the same way. What differs
 * is the lifecycle: each step's output is written down as it commits, so an instance that is
 * interrupted resumes at the step it reached rather than from the beginning, and the instance ends
 * by itself once the chain has run.
 *
 * <p>When a step fails and its retries are spent, the instance ends abnormally instead, and the
 * default rollback undoes every step that finished, in reverse, through their {@code compensate}
 * methods: the order is cancelled, then the money is released. A step with no {@code compensate}
 * is skipped, which is why the desk's book is left alone.
 */
public final class OrderExecutorOperator implements BoundedOperator {

    /**
     * The order an instance is started with. The entry channel declares this type, so the first
     * message is decoded into it: {@code --text '{"symbol": "EUR/USD", "quantity": 10}' --channel order}.
     */
    public record OrderTicket(String symbol, int quantity) {}

    /**
     * The operator's stable identity, and the name {@code flz operator start order_executor}
     * resolves. It is durable, so changing it declares a new operator rather than renaming this
     * one.
     */
    @Override
    @Nonnull
    public String factoryId() {
        return "order_executor";
    }

    /**
     * Invoked once at registration to declare the chain.
     *
     * <p>Two steps in order, then a fan-out. The placement is where the chain stops being a line:
     * confirming the fill and booking the order are two independent consequences of it, and
     * neither waits for the other. Each arm of {@code branch} receives the same handle, so the
     * declaration is exactly two {@code into} statements on the placed channel, written as one.
     *
     * <p>The driver runs the chain level by level: every step of a level commits before any of its
     * children run, so the desk's order of events is fixed rather than a race. A step that fails
     * poisons its whole level, which is why a refusal on either arm unwinds what the other arm did.
     *
     * <p>Nothing ends on a sink: the last step ends the instance with its result, which
     * {@code flz operator info} shows. Nothing names a channel either, so each one carries the
     * name of the step that produces it, which is what appears in the journal and in an error;
     * {@code named(...)} overrides that where a stable name of your own matters more.
     */
    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(OrderTicket.class, "order")
                .into(ReserveFundsTasklet.class)
                .into(PlaceOrderTasklet.class)
                .branch(
                        fill -> fill.into(ConfirmFillTasklet.class),
                        book -> book.into(RecordOrderTasklet.class));
    }
}
