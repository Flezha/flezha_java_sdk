// topology.unknown_field: `ack` is a setting of `effectively_once`; `at_least_once` carries none.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

final class Misplaced implements Operator {
    @Override
    public String factoryId() {
        return "ack_under_at_least_once";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.AtLeastOnce(new Ack.Checkpoint()));
    }
}
