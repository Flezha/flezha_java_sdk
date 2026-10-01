// topology.invalid_setting: a run is `flow` or `steps`; the retired kind literals name no variant.
package nowhere;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;

final class Misplaced implements Operator {
    @Override
    public String factoryId() {
        return "kind_literal";
    }

    @Override
    public void channels(ChannelBuilder c) {}

    @Override
    public Run run() {
        return new Run.Bounded();
    }
}
