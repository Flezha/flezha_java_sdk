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
 * <p>The steps are written as {@code Procedure} classes rather than with the {@code @Procedure}
 * annotation, because a step that has something to undo needs {@link #compensate}, and the
 * annotation knows only {@code run}. The six type arguments are, in order, the input, the state,
 * the output, the change, the clock and the configuration.
 */
public final class ReserveFunds
        implements Procedure<OrderTicket, ReserveFunds.Reservation, ReserveFunds.Reserved, ReserveFunds.Taken, WallClock, Void> {

    /** What this step remembers. The undo handle belongs here: state survives a restart, output does not. */
    public static final class Reservation {
        public String id = "";
    }

    /** The one change it records. Applying it twice leaves the same id, which a resume needs. */
    public record Taken(String id) implements Change {}

    /** What it hands on, and, with {@code released}, what its undo emits. */
    public record Reserved(OrderTicket order, String reservationId, boolean released) {}

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
        // The activation token is the same on a replay, so a resumed step reserves under the id it
        // reserved under the first time rather than putting a second sum aside.
        String reservationId = "res-" + ctx.activationToken();
        state.apply(new Taken(reservationId));
        ctx.emit(new Reserved(order, reservationId, false));
    }

    @Override
    public boolean compensateOverridden() {
        return true;
    }

    /**
     * The undo. The workflow driver calls it only when the instance ends abnormally, in reverse
     * order of the steps that finished, handing back what this step emitted. It has to be safe to
     * call twice: a crash part way through a rollback runs it again.
     */
    @Override
    public void compensate(List<Reserved> completedOutput, StateHandle<Reservation, Taken> state, ProcedureCtx ctx)
            throws ProcedureException {
        for (Reserved reserved : completedOutput) {
            ctx.emit(new Reserved(reserved.order(), reserved.reservationId(), true));
        }
    }
}
