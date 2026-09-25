package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Java mirror of the Rust test app's {@code HybridChannelUnboundedOperator}.
 * Demonstrates {@code alsoToPluginWith(...)}: the producer Tasklet emits a
 * {@code Long} value that fans out to an inter-tasklet consumer and is also
 * serialized to bytes for the {@code hybrid_indicators} plugin. The
 * serializer throws on the sentinel {@code 0xDEADBEEF} so a user can
 * observe the failure-isolation guarantee end-to-end.
 */
public final class HybridChannelUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "hybrid_channel";
    }

    @Override
    public void channels(ChannelBuilder c) {
        var sHybridProducerTasklet = c.input(String.class, "input").into(HybridProducerTasklet.class);
        sHybridProducerTasklet.sink("hybrid_indicator", v -> { if (v == 0xDEAD_BEEFL) { throw new RuntimeException("hybrid_indicators: serializer rejected sentinel 0xDEADBEEF"); } return ByteBuffer.allocate(4) .order(ByteOrder.BIG_ENDIAN) .putInt(v.intValue()) .array(); }).into(HybridConsumerTasklet.class);
    }

    public static final class HybridProducerTasklet
            implements Tasklet<String, HybridProducerTasklet.State, Long, NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "hybrid_producer_tasklet";
        }

        @Override
        public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new State();
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
            long n;
            try {
                n = Long.parseLong(input);
            } catch (NumberFormatException e) {
                n = 0L;
            }
            try {
                ctx.emit(n);
            } catch (TaskletException.PluginSerializerFailure e) {
                System.err.println("hybrid_producer_tasklet: serializer failed on `" + e.channel() + "`: "
                        + e.getMessage());
            }
        }

        public record State() {}
    }

    public static final class HybridConsumerTasklet
            implements Tasklet<Long, HybridConsumerTasklet.State, Void, NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "hybrid_consumer_tasklet";
        }

        @Override
        public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new State();
        }

        @Override
        public void run(Long input, StateHandle<State, NoChange> state, TaskletCtx ctx) {
            System.out.println("hybrid_consumer_tasklet: received " + input);
        }

        public record State() {}
    }
}
