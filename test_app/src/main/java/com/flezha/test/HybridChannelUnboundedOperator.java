package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    @Tasklet.Spec(name = "hybrid_producer_tasklet")
    public static final class HybridProducerTasklet implements Tasklet<String, Void, Long> {

        @Override
        public void run(String input, Ctx<Void, Long> ctx) throws TaskletException {
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
    }

    @Tasklet.Spec(name = "hybrid_consumer_tasklet")
    public static final class HybridConsumerTasklet implements Tasklet<Long, Void, Void> {

        @Override
        public void run(Long input, Ctx<Void, Void> ctx) {
            System.out.println("hybrid_consumer_tasklet: received " + input);
        }
    }
}
