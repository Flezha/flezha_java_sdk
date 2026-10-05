package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code typed_once_steps}: a {@code ONCE} steps relay whose entry
 * channel {@code order} decodes a JSON ticket. Its one tasklet prints the ticket's text, emits it to
 * the {@code placed} sink and completes the instance with it, so the message a spawn or a send
 * carried is observable on the runner's stdout, on the sink and in the completion.
 */
public final class TypedOnceRelay implements Relay {

    public static final String FACTORY_ID = "typed_once_steps";

    /** The ticket the entry channel decodes with the SDK's default JSON codec. */
    public record Ticket(String text) {}

    @Override
    public String factoryId() {
        return FACTORY_ID;
    }

    @Override
    public Run run() {
        return Run.steps();
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(Ticket.class, "order").into(OncePlaced.class).sink("placed");
    }

    @Tasklet.Spec(name = "once_placed")
    public static final class OncePlaced implements Tasklet<Ticket, Void, String> {

        @Override
        public void run(Ticket ticket, Ctx<Void, String> ctx) throws TaskletException {
            System.out.println("once_ran " + ticket.text());
            System.out.flush();
            ctx.emit("placed:" + ticket.text());
            ctx.complete(ticket.text());
        }
    }
}
