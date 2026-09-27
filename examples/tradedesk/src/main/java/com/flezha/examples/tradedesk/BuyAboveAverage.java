package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.OrderExecutorOperator.OrderTicket;
import com.flezha.examples.tradedesk.TrackPrices.Move;
import com.flezha.sdk.authoring.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Buys when a price is far enough above its symbol's average.
 *
 * <p>It remembers nothing: the move it is handed already carries the average, so the decision is a
 * function of one value, and a restart costs this stage nothing because there is nothing to bring
 * back. That is why it is written with {@code @Tasklet} and no state, beside {@link TrackPrices},
 * which keeps the window.
 *
 * <p>{@code run} takes the context because it does two things per input: it starts a bounded operator and
 * it emits. {@code emits} declares the output type, which a {@code void} return cannot.
 */
@Tasklet(id = "buy_above_average", emits = String.class)
public final class BuyAboveAverage {

    /** How far above its average a price has to be before the desk buys, in percent. */
    private static final double BUY_ABOVE_PCT = 2.0;

    /**
     * How much the desk buys for each percent a price is above its average: a stronger move buys
     * more, and a big enough one asks for more than the paper exchange fills.
     */
    private static final int UNITS_PER_PERCENT = 10;

    public void run(Move move, TaskletCtx ctx) throws TaskletException {
        double percent = (move.price() - move.average()) / move.average() * 100.0;
        if (percent < BUY_ABOVE_PCT) {
            return;
        }
        int quantity = (int) Math.round(percent * UNITS_PER_PERCENT);

        // No id: the controller names the instance. A replay of this invocation issues no request,
        // so re-running an input never places a second order. The ticket travels as JSON, which
        // the order's entry channel decodes back into an OrderTicket.
        ctx.spawnBounded("order_executor", new OrderTicket(move.symbol(), quantity));

        ctx.emit(String.format(
                "%s %.4f is %.2f%% above its average of %.4f, buying %d",
                move.symbol(), move.price(), percent, move.average(), quantity));
    }
}
