package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PlaceOrder.PlacedOrder;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Asks the exchange whether the order filled, on one arm of the fan-out. The paper exchange
 * rejects anything above its own size, which is what makes this the step that fails, and takes
 * ten seconds to answer, which leaves time to stop the runner in the middle of an order.
 *
 * <p>It remembers nothing and has nothing to undo, so it declares only {@code run}. It
 * ends the instance with a result rather than emitting one: the order's outcome is the instance's
 * own answer, which {@code flz operator info} reads back, so the desk needs no sink here.
 *
 * <p>A failure here is retried first, and once the retries are spent the instance ends abnormally.
 * Everything that finished is then undone in reverse, across both arms: the order is cancelled and
 * the money released, while the desk's book is skipped because it declares no {@code compensate}.
 */
@Tasklet(id = "confirm_fill")
public final class ConfirmFill {

    /** The largest order the paper exchange fills in one go. Anything above it is rejected. */
    private static final int EXCHANGE_MAX = 100;

    public void run(PlacedOrder placed, TaskletCtx ctx) throws TaskletException {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new TaskletException("stopped while waiting for the exchange");
        }
        int quantity = placed.order().quantity();
        if (quantity > EXCHANGE_MAX) {
            throw new TaskletException(
                    "the exchange fills at most " + EXCHANGE_MAX + " a time, and this is " + quantity);
        }
        ctx.complete("filled " + placed.order().symbol() + " " + quantity + " as " + placed.orderId());
    }
}
