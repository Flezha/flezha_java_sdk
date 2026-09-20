package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.PaperOrderOperative.OrderTicket;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Step one: put the money for the order aside.
 *
 * <p>A step that keeps state or has something to undo implements {@link Procedure} directly, and
 * this one does both. The six type arguments are, in order, the input, the state, the output, the
 * change, the clock and the configuration. The desk's stateless steps take the
 * {@code @Procedure} short form instead, which is why the operative wires some classes by their
 * own name and others by a generated one.
 */
public final class ReserveFunds
        implements Procedure<OrderTicket, ReserveFunds.Reservation, ReserveFunds.Reserved, ReserveFunds.Taken, WallClock, Void> {

    /** What this step remembers. The undo handle belongs here: state survives a restart, output does not. */
    public static final class Reservation {
        public String id = "";
    }

    /** The one change it records. Applying it twice leaves the same id, which a resume needs. */
    public record Taken(String id) implements Change {}

    /** What it hands on: the order, and the reservation holding the money for it. */
    public record Reserved(OrderTicket order, String reservationId) {}

    @Override
    public String name() {
        return "reserve_funds";
    }

    /** The journal has to know how to write the state and its changes. */
    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(Reservation.class, Taken.class);
    }

    @Override
    public Reservation initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
        return new Reservation();
    }

    @Override
    public void applyChange(Reservation state, Taken change) {
        state.id = change.id();
    }

    @Override
    public void run(OrderTicket order, StateHandle<Reservation, Taken> state, ProcedureCtx ctx)
            throws ProcedureException {
        // ctx.stableId(), not a random id: a re-executed step derives the same one, so the ledger
        // sees a retry rather than a second sum put aside.
        String reservationId = "res-" + ctx.stableId();
        state.apply(new Taken(reservationId));
        ctx.emit(new Reserved(order, reservationId));
    }

    @Override
    public boolean compensateOverridden() {
        return true;
    }

    /**
     * The undo. The workflow driver calls it only when the instance ends abnormally, in reverse
     * order of the steps that finished. It has to be safe to call twice: a crash part way through
     * a rollback runs it again.
     *
     * <p>The id comes from the state, not from {@code completedOutput}: state is restored on
     * resume and emitted output is not, so after a crash-resumed rollback that list can be empty.
     */
    @Override
    public void compensate(List<Reserved> completedOutput, StateHandle<Reservation, Taken> state, ProcedureCtx ctx)
            throws ProcedureException {
        System.out.println("[undo] released " + state.get().id);
    }
}
