package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Tasklet;

/**
 * Same Tasklet topology as {@link StatefulCounterRelay} but with
 * persistence disabled at the relay-class level, over tasklets that
 * journal a Change: the class's switch, not its Change types, is what it
 * declares. Used by integration tests to verify the toggle-off drain discards
 * Changes without serialization or wire I/O.
 */
public final class PersistenceOffRelay implements Relay {

    @Override
    public String factoryId() {
        return "persistence_off_counter";
    }

    @Override
    public Durable durable() {
        return new Durable.Memory();
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(PersistenceOffEntryTasklet.class)
                .named("ticks")
                .into(PersistenceOffCounterTasklet.class);
    }

    /** The {@link CounterEntryTasklet} parse under this relay's own node identity. */
    @Tasklet.Spec(name = "persistence_off_entry_tasklet")
    public static final class PersistenceOffEntryTasklet extends CounterEntryTasklet {}

    /** The {@link CounterTasklet} fold under this relay's own node identity. */
    @Tasklet.Spec(name = "persistence_off_counter_tasklet")
    public static final class PersistenceOffCounterTasklet extends CounterTasklet {}
}
