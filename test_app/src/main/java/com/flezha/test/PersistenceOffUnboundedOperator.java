package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Same Tasklet topology as {@link StatefulCounterUnboundedOperator} but with
 * persistence disabled at the operator-class level, over tasklets that
 * journal a Change: the class's switch, not its Change types, is what it
 * declares. Used by integration tests to verify the toggle-off drain discards
 * Changes without serialization or wire I/O.
 */
public final class PersistenceOffUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "persistence_off_counter";
    }

    @Override
    public boolean persistenceEnabled() {
        return false;
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(PersistenceOffEntryTasklet.class)
                .named("ticks")
                .into(PersistenceOffCounterTasklet.class);
    }

    /** The {@link CounterEntryTasklet} parse under this operator's own node identity. */
    public static final class PersistenceOffEntryTasklet extends CounterEntryTasklet {
        @Override
        public String name() {
            return "persistence_off_entry_tasklet";
        }
    }

    /** The {@link CounterTasklet} fold under this operator's own node identity. */
    public static final class PersistenceOffCounterTasklet extends CounterTasklet {
        @Override
        public String name() {
            return "persistence_off_counter_tasklet";
        }
    }
}
