// topology.unknown_field: `each` is a setting of `steps`; a flow run has no member of that name.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

final class Misplaced implements Operator {
    @Override
    public String factoryId() {
        return "each_under_flow";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.AtLeastOnce(), Each.PER_INPUT);
    }
}
