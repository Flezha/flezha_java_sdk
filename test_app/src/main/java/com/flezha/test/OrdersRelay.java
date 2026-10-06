package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.math.BigDecimal;

/**
 * The {@code relay_api} fixture: {@code place} reads a JSON order, is described, and answers
 * through the output {@code placed}; {@code audit} reads text and answers nothing.
 */
public final class OrdersRelay implements Relay {

    public record Order(String pair, BigDecimal amount) {}

    public record Placed(String pair, BigDecimal amount, String status) {}

    @Override
    public String factoryId() {
        return "orders";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(Order.class, "place").describe("Places an order").into(Place.class).output("placed");
        c.input(String.class, "audit").into(Audit.class);
    }

    @Tasklet.Spec(name = "orders_place")
    public static final class Place implements Tasklet<Order, Void, Placed> {

        @Override
        public void run(Order order, Ctx<Void, Placed> ctx) throws TaskletException {
            ctx.emit(new Placed(order.pair(), order.amount(), "placed"));
        }
    }

    @Tasklet.Spec(name = "orders_audit")
    public static final class Audit implements Tasklet<String, Void, Void> {

        @Override
        public void run(String line, Ctx<Void, Void> ctx) throws TaskletException {}
    }
}
