package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PlaceOrder.PlacedOrder;
import com.flezha.sdk.authoring.Tasklet;

/**
 * Writes the placed order into the desk's book, on its own arm of the fan-out.
 *
 * <p>Confirming the fill and booking the order are two independent consequences of the same event,
 * and neither waits for the other, which is what a branch is for.
 *
 * <p>The plainest shape a tasklet has: a value in, nothing kept, nothing out. A {@code void}
 * return declares no output channel at all.
 *
 * <p>It carries no {@code compensate} on purpose. A rollback undoes what the desk did to the world,
 * not what it wrote down about it: the attempt happened, and the book says so even when the order
 * was cancelled again.
 */
@Tasklet(id = "record_order")
public final class RecordOrder {

    public void run(PlacedOrder placed) {
        System.out.println(
                "[book] " + placed.order().symbol() + " " + placed.order().quantity() + " as " + placed.orderId());
    }
}
