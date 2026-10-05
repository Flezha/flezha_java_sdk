// topology.unknown_field: `ack` is a flow setting; a steps run has no member of that name.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

final class Misplaced implements Relay {
    @Override
    public String factoryId() {
        return "ack_under_steps";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Run run() {
        return new Run.Steps(Each.ONCE, OnFailure.UNDO_ALL, new Ack.Checkpoint());
    }
}
