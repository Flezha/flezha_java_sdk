// topology.unknown_field: `sleep_after` is a setting of `local` and `remote`; `durable: false` keeps nothing to sleep.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import java.time.Duration;

final class Misplaced implements Relay {
    @Override
    public String factoryId() {
        return "sleep_after_in_memory";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Durable durable() {
        return new Durable.Memory(Duration.ofSeconds(1));
    }
}
