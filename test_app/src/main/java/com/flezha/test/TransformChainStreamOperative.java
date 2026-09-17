package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code TransformChainOperative}: every value
 * transform on one edge. {@code drop} is filtered out, a comma list fans out
 * into one value per element, each is upper-cased and keyed on its prefix.
 */
public final class TransformChainStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "transform_chain";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "trades")
                .filter(t -> !t.equals("drop"))
                .flatMap(String.class, t -> List.of(t.split(",")))
                .map(String.class, String::toUpperCase)
                .keyBy(DynamicEntityStreamOperative::key)
                .into(TransformLabelProcedure.class)
                .keyed()
                .sink("transformed");
    }

    public static final class TransformLabelProcedure
            implements Procedure<String, TransformLabelProcedure.State, String, NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "transform_label_procedure";
        }

        @Override
        public State initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
            return new State();
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, ProcedureCtx ctx)
                throws ProcedureException {
            ctx.emit(input);
        }

        public record State() {}
    }
}
